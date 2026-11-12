package com.cloudstorage.controller;

import com.cloudstorage.dto.FileResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.UserPrincipal;
import com.cloudstorage.service.FileService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private static final Logger log = LoggerFactory.getLogger(FileController.class);
    private static final Set<String> PATCH_FIELDS = Set.of("name", "folderId");

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileResponse> upload(@AuthenticationPrincipal UserPrincipal user,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(required = false) UUID folderId) {
        FileResponse created = files.upload(user.id(), folderId, file);
        return ResponseEntity.created(URI.create("/api/files/" + created.id())).body(created);
    }

    @GetMapping
    public List<FileResponse> list(@AuthenticationPrincipal UserPrincipal user,
                                   @RequestParam(required = false) UUID folderId) {
        return files.list(user.id(), folderId);
    }

    @GetMapping("/{id}")
    public FileResponse get(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        return files.get(id, user.id());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@AuthenticationPrincipal UserPrincipal user,
                                                          @PathVariable UUID id) {
        FileService.DownloadFile download = files.openDownload(id, user.id());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.metadata().name(), StandardCharsets.UTF_8)
                .build();
        StreamingResponseBody body = output -> {
            try {
                files.writeDownload(download, output);
            } catch (IOException exception) {
                log.warn("Download stream was interrupted for file id {}", id, exception);
                throw exception;
            }
        };
        return ResponseEntity.ok()
                .contentType(safeMediaType(download.metadata().mimeType()))
                .contentLength(download.metadata().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FileResponse patch(@AuthenticationPrincipal UserPrincipal user,
                              @PathVariable UUID id,
                              @RequestBody Map<String, Object> changes) {
        rejectUnknownFields(changes, PATCH_FIELDS);
        boolean hasName = changes.containsKey("name");
        String name = null;
        if (hasName) {
            Object rawName = changes.get("name");
            if (!(rawName instanceof String text)) {
                throw badPatch("name must be a string.");
            }
            name = text;
        }
        boolean hasFolderId = changes.containsKey("folderId");
        UUID folderId = hasFolderId ? parseUuidOrNull(changes.get("folderId"), "folderId") : null;
        return files.patch(id, user.id(), hasName, name, hasFolderId, folderId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        files.delete(id, user.id());
        return ResponseEntity.noContent().build();
    }

    private MediaType safeMediaType(String value) {
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    static UUID parseUuidOrNull(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                throw badPatch(field + " must be a UUID or null.");
            }
        }
        throw badPatch(field + " must be a UUID or null.");
    }

    static void rejectUnknownFields(Map<String, Object> changes, Set<String> allowed) {
        if (changes == null || changes.isEmpty() || !allowed.containsAll(changes.keySet())) {
            throw badPatch("The request contains no supported fields or contains unknown fields.");
        }
    }

    private static ApiException badPatch(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_patch", message);
    }
}
