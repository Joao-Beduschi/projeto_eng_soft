package com.cloudstorage.service;

import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.StoredObject;
import java.net.URLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class StorageService {
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final int BUFFER_SIZE = 64 * 1024;

    private final Path root;
    private final Path tempRoot;
    private long reservedBytes;

    public StorageService(@Value("${app.storage.root}") String configuredRoot) {
        try {
            Path requestedRoot = Path.of(configuredRoot).toAbsolutePath().normalize();
            Files.createDirectories(requestedRoot);
            this.root = requestedRoot.toRealPath();
            this.tempRoot = root.resolve(".tmp");
            Files.createDirectories(tempRoot);
            if (Files.isSymbolicLink(tempRoot)
                    || !tempRoot.toRealPath().startsWith(root)) {
                throw new IOException("Temporary storage directory is not private to the storage root");
            }
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not initialize private file storage", exception);
        }
    }

    public StoredObject store(UUID storageKey, String displayName, InputStream input,
                              long expectedSize, long maxSize)
            throws IOException {
        if (expectedSize < 0 || expectedSize > maxSize) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                    "The uploaded file exceeds the configured size limit.");
        }

        reserveSpace(expectedSize);
        try {
            Path target = resolve(storageKey);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Storage object already exists");
            }

            Path temporary = Files.createTempFile(tempRoot, storageKey + "-", ".part");
            boolean committed = false;
            try {
                MessageDigest digest = sha256Digest();
                long size = 0;
                byte[] bytes = new byte[BUFFER_SIZE];

                try (FileChannel output = FileChannel.open(temporary,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    int read;
                    while ((read = input.read(bytes)) != -1) {
                        if (read == 0) {
                            continue;
                        }
                        size += read;
                        if (size > maxSize) {
                            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                                    "The uploaded file exceeds the configured size limit.");
                        }
                        if (size > expectedSize) {
                            throw new ApiException(HttpStatus.BAD_REQUEST, "upload_size_mismatch",
                                    "The uploaded content length did not match the request.");
                        }
                        digest.update(bytes, 0, read);
                        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, read);
                        while (buffer.hasRemaining()) {
                            output.write(buffer);
                        }
                    }
                    if (size != expectedSize) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "upload_size_mismatch",
                                "The uploaded content length did not match the request.");
                    }
                    output.force(true);
                }

                moveIntoPlace(temporary, target);
                committed = true;
                forceDirectory(root);
                return new StoredObject(size, HexFormat.of().formatHex(digest.digest()), detectMimeType(displayName));
            } finally {
                if (!committed) {
                    Files.deleteIfExists(temporary);
                }
            }
        } finally {
            releaseSpace(expectedSize);
        }
    }

    public InputStream retrieve(UUID storageKey) throws IOException {
        Path target = resolve(storageKey);
        verifyReadable(target);
        return Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
    }

    public void verifyReadable(UUID storageKey) throws IOException {
        verifyReadable(resolve(storageKey));
    }

    private void verifyReadable(Path target) throws IOException {
        if (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || !target.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root)) {
            throw new IOException("Stored object is missing or invalid");
        }
    }

    public void delete(UUID storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    public Set<UUID> listStoredKeys() throws IOException {
        Set<UUID> keys = new HashSet<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    keys.add(UUID.fromString(entry.getFileName().toString()));
                } catch (IllegalArgumentException ignored) {
                    // Ignore operational files and any names that are not generated UUIDs.
                }
            }
        }
        return keys;
    }

    public void removeExpiredTemporaryFiles(Instant cutoff) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(tempRoot)) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private Path resolve(UUID storageKey) throws IOException {
        if (storageKey == null) {
            throw new IOException("Invalid storage key");
        }
        Path result = root.resolve(storageKey.toString()).normalize();
        if (!result.getParent().equals(root)) {
            throw new IOException("Invalid storage key");
        }
        return result;
    }

    private void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target);
        }
    }

    private synchronized void reserveSpace(long bytes) throws IOException {
        long usableBytes = Files.getFileStore(root).getUsableSpace();
        if (bytes > usableBytes - reservedBytes) {
            throw new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "insufficient_storage",
                    "There is not enough free storage space to accept this file.");
        }
        reservedBytes += bytes;
    }

    private synchronized void releaseSpace(long bytes) {
        reservedBytes -= bytes;
    }

    private String detectMimeType(String displayName) {
        try {
            String mime = URLConnection.guessContentTypeFromName(displayName);
            if (mime != null && mime.length() <= 255 && mime.chars().noneMatch(Character::isISOControl)) {
                return mime;
            }
        } catch (RuntimeException exception) {
            log.debug("Could not infer a MIME type from the file's display name", exception);
        }
        return "application/octet-stream";
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException exception) {
            log.debug("Directory sync is not supported by this filesystem");
        }
    }
}
