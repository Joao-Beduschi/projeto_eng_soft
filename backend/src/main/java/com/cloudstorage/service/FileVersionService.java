package com.cloudstorage.service;

import com.cloudstorage.dto.FileVersionHistoryResponse;
import com.cloudstorage.dto.FileVersionResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.FileRecord;
import com.cloudstorage.model.FileVersionRecord;
import com.cloudstorage.model.StoredObject;
import com.cloudstorage.repository.DatabaseConstraints;
import com.cloudstorage.repository.FileRepository;
import com.cloudstorage.repository.FileVersionRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileVersionService {
    private static final Logger log = LoggerFactory.getLogger(FileVersionService.class);
    private static final int MAX_CHANGE_MESSAGE_LENGTH = 1000;

    private final FileRepository files;
    private final FileVersionRepository versions;
    private final StorageService storage;
    private final TransactionTemplate transactions;
    private final long maxFileSize;

    @Autowired
    public FileVersionService(FileRepository files,
                              FileVersionRepository versions,
                              StorageService storage,
                              TransactionTemplate transactions,
                              @Value("${app.storage.max-file-size-bytes:104857600}") long maxFileSize) {
        this.files = files;
        this.versions = versions;
        this.storage = storage;
        this.transactions = transactions;
        this.maxFileSize = maxFileSize;
    }

    public FileVersionHistoryResponse list(UUID fileId, UUID ownerId) {
        FileRecord file = findReadyFile(fileId, ownerId);
        FileVersionRecord current = versions.findCurrentReadyOwned(fileId, ownerId)
                .orElseThrow(() -> storageMetadataError(fileId));
        List<FileVersionResponse> history = versions.listReadyOwned(fileId, ownerId).stream()
                .map(version -> toResponse(version, version.id().equals(file.currentVersionId())))
                .toList();
        return new FileVersionHistoryResponse(fileId, current.versionNumber(),
                etag(fileId, current.versionNumber()), history);
    }

    public FileVersionResponse get(UUID fileId, int versionNumber, UUID ownerId) {
        FileRecord file = findReadyFile(fileId, ownerId);
        FileVersionRecord version = versions.findReadyOwned(fileId, versionNumber, ownerId)
                .orElseThrow(() -> notFound("file_version"));
        return toResponse(version, version.id().equals(file.currentVersionId()));
    }

    public FileVersionResponse upload(UUID fileId, UUID ownerId, String ifMatch,
                                      MultipartFile upload, String changeMessage) {
        if (upload == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file_required");
        }
        long size = upload.getSize();
        validateSize(size);
        Reservation reservation = reserve(fileId, ownerId, ifMatch, size,
                normalizeMessage(changeMessage), null);
        boolean storageCommitted = false;
        try (InputStream input = upload.getInputStream()) {
            StoredObject stored = storage.store(reservation.version().storageKey(),
                    reservation.file().name(), input, size, maxFileSize);
            storageCommitted = true;
            return complete(reservation, stored);
        } catch (ApiException exception) {
            abort(reservation, storageCommitted);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            abort(reservation, storageCommitted);
            log.warn("File revision upload failed for file id {}", fileId, exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "version_upload_failed");
        }
    }

    public FileVersionResponse restore(UUID fileId, int sourceVersionNumber, UUID ownerId,
                                        String ifMatch, String changeMessage) {
        String message = normalizeMessage(changeMessage);
        if (message == null) {
            message = "Restored from version " + sourceVersionNumber;
        }
        Reservation reservation = reserve(fileId, ownerId, ifMatch, null, message, sourceVersionNumber);
        boolean storageCommitted = false;
        try (InputStream input = storage.retrieve(reservation.source().storageKey())) {
            StoredObject stored = storage.store(reservation.version().storageKey(),
                    reservation.file().name(), input, reservation.source().sizeBytes(), maxFileSize);
            storageCommitted = true;
            if (!reservation.source().sha256().equals(stored.sha256())) {
                throw new IOException("Restored bytes did not match the selected historical revision");
            }
            return complete(reservation, stored);
        } catch (ApiException exception) {
            abort(reservation, storageCommitted);
            throw exception;
        } catch (IOException | RuntimeException exception) {
            abort(reservation, storageCommitted);
            log.error("Could not restore file {} from version {}", fileId, sourceVersionNumber, exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "version_restore_failed");
        }
    }

    public VersionDownload openDownload(UUID fileId, int versionNumber, UUID ownerId) {
        FileRecord file = findReadyFile(fileId, ownerId);
        FileVersionRecord version = versions.findReadyOwned(fileId, versionNumber, ownerId)
                .orElseThrow(() -> notFound("file_version"));
        try {
            storage.verifyReadable(version.storageKey());
            return new VersionDownload(file.name(), version,
                    toResponse(version, version.id().equals(file.currentVersionId())));
        } catch (IOException exception) {
            log.error("Stored bytes are unavailable for file {} version {}", fileId, versionNumber, exception);
            throw storageMetadataError(fileId);
        }
    }

    public void writeDownload(VersionDownload download, OutputStream output) throws IOException {
        try (InputStream input = storage.retrieve(download.version().storageKey())) {
            input.transferTo(output);
        }
    }

    private Reservation reserve(UUID fileId, UUID ownerId, String ifMatch, Long uploadSize,
                                String changeMessage, Integer restoreVersionNumber) {
        int expectedVersion = parseIfMatch(fileId, ifMatch);
        try {
            return transactions.execute(status -> {
                FileRecord file = files.findReadyOwnedForUpdate(fileId, ownerId)
                        .orElseThrow(() -> notFound("file"));
                FileVersionRecord current = versions.findCurrentReadyOwned(fileId, ownerId)
                        .orElseThrow(() -> storageMetadataError(fileId));
                if (current.versionNumber() != expectedVersion) {
                    throw staleVersion(current.versionNumber());
                }

                FileVersionRecord source = null;
                long size;
                if (restoreVersionNumber != null) {
                    source = versions.findReadyOwned(fileId, restoreVersionNumber, ownerId)
                            .orElseThrow(() -> notFound("file_version"));
                    size = source.sizeBytes();
                } else {
                    size = uploadSize;
                }
                validateSize(size);
                if (current.versionNumber() == Integer.MAX_VALUE) {
                    throw new ApiException(HttpStatus.CONFLICT, "version_limit_reached");
                }

                FileVersionRecord pending = new FileVersionRecord(UUID.randomUUID(), fileId,
                        current.versionNumber() + 1, ownerId, null, UUID.randomUUID(), size,
                        null, changeMessage, "pending");
                FileVersionRecord reserved = versions.createPending(pending);
                return new Reservation(file, current, reserved, source);
            });
        } catch (DataIntegrityViolationException exception) {
            if (!DatabaseConstraints.isUniqueViolation(exception, "file_versions_file_id_version_number_key")) {
                throw exception;
            }
            throw new ApiException(HttpStatus.CONFLICT, "revision_in_progress");
        }
    }

    private FileVersionResponse complete(Reservation reservation, StoredObject stored) {
        transactions.executeWithoutResult(status -> {
            if (versions.markReady(reservation.version().id(), reservation.file().id(),
                    stored.sizeBytes(), stored.sha256()) != 1) {
                throw new IllegalStateException("Pending revision metadata is unavailable");
            }
            int updated = files.advanceCurrentVersion(reservation.file().id(),
                    reservation.file().ownerId(), reservation.current().id(),
                    reservation.version().id(), reservation.version().storageKey(),
                    stored.sizeBytes(), stored.sha256(), stored.detectedMimeType());
            if (updated != 1) {
                throw staleVersion(reservation.current().versionNumber());
            }
        });
        FileVersionRecord committed = new FileVersionRecord(reservation.version().id(),
                reservation.version().fileId(), reservation.version().versionNumber(),
                reservation.version().uploaderUserId(), reservation.version().createdAt(),
                reservation.version().storageKey(), stored.sizeBytes(), stored.sha256(),
                reservation.version().changeMessage(), "ready");
        return toResponse(committed, true);
    }

    private void abort(Reservation reservation, boolean storageCommitted) {
        try {
            if (versions.findReadyOwned(reservation.file().id(), reservation.version().versionNumber(),
                    reservation.file().ownerId()).isPresent()) {
                // A commit may have succeeded even if the connection failed while reporting its result.
                // Keep both its bytes and metadata rather than risking a dangling ready revision.
                return;
            }
        } catch (RuntimeException exception) {
            log.warn("Could not determine whether revision {} committed; leaving its bytes for reconciliation",
                    reservation.version().id(), exception);
            return;
        }
        if (storageCommitted) {
            try {
                storage.delete(reservation.version().storageKey());
            } catch (IOException exception) {
                log.warn("Could not remove bytes from failed revision {}", reservation.version().id(), exception);
            }
        }
        try {
            versions.deletePending(reservation.version().id());
        } catch (RuntimeException exception) {
            log.warn("Could not remove pending revision metadata {}", reservation.version().id(), exception);
        }
    }

    private FileRecord findReadyFile(UUID fileId, UUID ownerId) {
        return files.findReadyOwned(fileId, ownerId).orElseThrow(() -> notFound("file"));
    }

    private void validateSize(long size) {
        if (size < 0 || size > maxFileSize) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large");
        }
    }

    private String normalizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String normalized = message.strip();
        if (normalized.length() > MAX_CHANGE_MESSAGE_LENGTH
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_change_message");
        }
        return normalized;
    }

    private int parseIfMatch(UUID fileId, String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "if_match_required");
        }
        String prefix = "\"" + fileId + "-v";
        if (!value.startsWith(prefix) || !value.endsWith("\"") || value.indexOf(',') >= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_file_etag");
        }
        try {
            int version = Integer.parseInt(value.substring(prefix.length(), value.length() - 1));
            if (version > 0 && etag(fileId, version).equals(value)) {
                return version;
            }
        } catch (NumberFormatException ignored) {
            // Return the same safe error for malformed and non-positive tags.
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_file_etag");
    }

    public static String etag(UUID fileId, int versionNumber) {
        return "\"" + fileId + "-v" + versionNumber + "\"";
    }

    private ApiException staleVersion(int currentVersion) {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, "stale_file_version",
                "error.stale_file_version", Integer.toString(currentVersion));
    }

    private ApiException storageMetadataError(UUID fileId) {
        log.error("Current version metadata is unavailable for file {}", fileId);
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "version_metadata_unavailable");
    }

    private ApiException notFound(String resource) {
        return new ApiException(HttpStatus.NOT_FOUND, resource + "_not_found");
    }

    private FileVersionResponse toResponse(FileVersionRecord version, boolean current) {
        return new FileVersionResponse(version.id(), version.fileId(), version.versionNumber(),
                version.uploaderUserId(), version.createdAt(), version.sizeBytes(),
                version.sha256(), version.changeMessage(), current);
    }

    public record VersionDownload(String fileName, FileVersionRecord version,
                                  FileVersionResponse metadata) {
    }

    private record Reservation(FileRecord file, FileVersionRecord current,
                               FileVersionRecord version, FileVersionRecord source) {
    }
}
