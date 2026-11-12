package com.cloudstorage.service;

import com.cloudstorage.dto.FileResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.FileRecord;
import com.cloudstorage.model.StoredObject;
import com.cloudstorage.repository.FileRepository;
import com.cloudstorage.repository.FolderRepository;
import com.cloudstorage.validation.NameValidator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileService {
    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private final FileRepository files;
    private final FolderRepository folders;
    private final StorageService storage;
    private final long maxFileSize;

    public FileService(FileRepository files,
                       FolderRepository folders,
                       StorageService storage,
                       @Value("${app.storage.max-file-size-bytes:104857600}") long maxFileSize) {
        this.files = files;
        this.folders = folders;
        this.storage = storage;
        if (maxFileSize < 1) {
            throw new IllegalStateException("The configured maximum file size must be positive");
        }
        this.maxFileSize = maxFileSize;
    }

    public FileResponse upload(UUID ownerId, UUID folderId, MultipartFile upload) {
        if (upload == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file_required", "A file is required.");
        }
        String originalName = NameValidator.validate(upload.getOriginalFilename(), "file");
        long expectedSize = upload.getSize();
        if (expectedSize < 0 || expectedSize > maxFileSize) {
            throw tooLarge();
        }
        if (folderId != null && folders.findOwned(folderId, ownerId).isEmpty()) {
            throw notFound("folder");
        }

        UUID id = UUID.randomUUID();
        UUID storageKey = UUID.randomUUID();
        FileRecord pending = new FileRecord(id, ownerId, folderId, originalName, storageKey,
                expectedSize, "application/octet-stream", null, "pending", null, null);
        files.createPending(pending);

        boolean storageCommitted = false;
        boolean metadataReady = false;
        try (InputStream input = upload.getInputStream()) {
            StoredObject stored = storage.store(storageKey, originalName, input, expectedSize, maxFileSize);
            storageCommitted = true;
            if (!files.markReady(id, ownerId, stored.sha256(), stored.detectedMimeType())) {
                throw new IOException("Upload metadata could not be committed");
            }
            metadataReady = true;
            return toResponse(files.findReadyOwned(id, ownerId)
                    .orElseThrow(() -> new IOException("Committed upload metadata could not be read")));
        } catch (ApiException exception) {
            if (!metadataReady) {
                cleanupFailedUpload(id, storageKey, storageCommitted);
            }
            throw exception;
        } catch (IOException | RuntimeException exception) {
            if (!metadataReady) {
                cleanupFailedUpload(id, storageKey, storageCommitted);
            }
            log.warn("File upload failed for metadata id {}", id, exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "upload_failed",
                    "The file could not be stored.");
        }
    }

    public List<FileResponse> list(UUID ownerId, UUID folderId) {
        if (folderId != null && folders.findOwned(folderId, ownerId).isEmpty()) {
            throw notFound("folder");
        }
        return files.listReady(ownerId, folderId).stream().map(FileService::toResponse).toList();
    }

    public FileResponse get(UUID id, UUID ownerId) {
        return toResponse(findReady(id, ownerId));
    }

    public DownloadFile openDownload(UUID id, UUID ownerId) {
        FileRecord file = findReady(id, ownerId);
        try {
            storage.verifyReadable(file.storageKey());
            return new DownloadFile(toResponse(file), file.storageKey());
        } catch (IOException exception) {
            log.error("Stored object is unavailable for file metadata id {}", id, exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "stored_file_unavailable",
                    "The stored file is temporarily unavailable.");
        }
    }

    public void writeDownload(DownloadFile download, OutputStream output) throws IOException {
        try (InputStream input = storage.retrieve(download.storageKey())) {
            input.transferTo(output);
        }
    }

    public void delete(UUID id, UUID ownerId) {
        FileRecord deleting = files.markDeleting(id, ownerId).orElseThrow(() -> notFound("file"));
        try {
            storage.delete(deleting.storageKey());
        } catch (IOException exception) {
            log.error("Could not remove stored object for file metadata id {}", id, exception);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "delete_failed",
                    "The file could not be deleted right now. Cleanup will be retried.");
        }
        if (!files.deleteWithStatus(id, "deleting")) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "delete_incomplete",
                    "The file was removed but its metadata cleanup will be retried.");
        }
    }

    @Transactional
    public FileResponse patch(UUID id, UUID ownerId, boolean hasName, String requestedName,
                              boolean hasFolderId, UUID folderId) {
        findReady(id, ownerId);
        if (!hasName && !hasFolderId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_patch", "At least one supported field is required.");
        }
        if (hasName) {
            String name = NameValidator.validate(requestedName, "file");
            if (!files.renameReady(id, ownerId, name)) {
                throw notFound("file");
            }
        }
        if (hasFolderId) {
            if (folderId != null && folders.findOwned(folderId, ownerId).isEmpty()) {
                throw notFound("folder");
            }
            if (!files.moveReady(id, ownerId, folderId)) {
                throw notFound("file");
            }
        }
        return get(id, ownerId);
    }

    public static FileResponse toResponse(FileRecord file) {
        return new FileResponse(file.id(), file.name(), file.sizeBytes(), file.mimeType(),
                file.sha256(), file.folderId(), file.createdAt(), file.updatedAt());
    }

    private FileRecord findReady(UUID id, UUID ownerId) {
        return files.findReadyOwned(id, ownerId).orElseThrow(() -> notFound("file"));
    }

    private void cleanupFailedUpload(UUID id, UUID storageKey, boolean storageCommitted) {
        if (storageCommitted) {
            try {
                storage.delete(storageKey);
            } catch (IOException exception) {
                log.warn("Could not clean up stored bytes after failed upload {}", id, exception);
            }
        }
        try {
            files.deleteWithStatus(id, "pending");
        } catch (RuntimeException exception) {
            log.warn("Could not clean up pending metadata after failed upload {}", id, exception);
        }
    }

    private ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                "The uploaded file exceeds the configured size limit.");
    }

    private ApiException notFound(String resource) {
        return new ApiException(HttpStatus.NOT_FOUND, resource + "_not_found", "The requested " + resource + " was not found.");
    }

    public record DownloadFile(FileResponse metadata, UUID storageKey) {
    }
}
