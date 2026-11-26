package com.cloudstorage.controller.api;

import com.cloudstorage.dto.FileResponse;
import com.cloudstorage.dto.FileVersionHistoryResponse;
import com.cloudstorage.dto.FileVersionResponse;
import com.cloudstorage.security.UserPrincipal;
import com.cloudstorage.service.FileService;
import com.cloudstorage.service.FileVersionService;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final FileService files;
    private final FileVersionService versions;

    public FileController(FileService files, FileVersionService versions) {
        this.files = files;
        this.versions = versions;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileResponse> upload(@AuthenticationPrincipal UserPrincipal user,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(required = false) UUID folderId) {
        FileResponse created = files.upload(user.id(), folderId, file);
        return ResponseEntity.created(URI.create("/api/files/" + created.id())).body(created);
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<FileVersionHistoryResponse> versions(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        FileVersionHistoryResponse history = versions.list(id, user.id());
        return ResponseEntity.ok().eTag(history.currentEtag()).body(history);
    }

    @PostMapping(path = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileVersionResponse> uploadVersion(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String changeMessage) {
        FileVersionResponse created = versions.upload(id, user.id(), ifMatch, file, changeMessage);
        return ResponseEntity.created(URI.create("/api/files/" + id + "/versions/"
                        + created.versionNumber()))
                .eTag(FileVersionService.etag(id, created.versionNumber()))
                .body(created);
    }

    @GetMapping("/{id}/versions/{versionNumber}")
    public ResponseEntity<FileVersionResponse> getVersion(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID id,
            @PathVariable int versionNumber) {
        FileVersionResponse version = versions.get(id, versionNumber, user.id());
        return ResponseEntity.ok()
                .eTag(FileVersionService.etag(id, versionNumber))
                .body(version);
    }

    @GetMapping("/{id}/versions/{versionNumber}/download")
    public ResponseEntity<StreamingResponseBody> downloadVersion(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID id,
            @PathVariable int versionNumber) {
        FileVersionService.VersionDownload download = versions.openDownload(id, versionNumber, user.id());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        StreamingResponseBody body = output -> {
            try {
                versions.writeDownload(download, output);
            } catch (IOException exception) {
                log.warn("Download stream was interrupted for file {} version {}", id, versionNumber, exception);
                throw exception;
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(download.metadata().sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(body);
    }

    @PostMapping("/{id}/versions/{versionNumber}/restore")
    public ResponseEntity<FileVersionResponse> restoreVersion(
            @AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID id,
            @PathVariable int versionNumber,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) RestoreVersionRequest request) {
        String changeMessage = request == null ? null : request.changeMessage();
        FileVersionResponse restored = versions.restore(id, versionNumber, user.id(), ifMatch, changeMessage);
        return ResponseEntity.created(URI.create("/api/files/" + id + "/versions/"
                        + restored.versionNumber()))
                .eTag(FileVersionService.etag(id, restored.versionNumber()))
                .body(restored);
    }

    public record RestoreVersionRequest(String changeMessage) {
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
        ItemPatch patch = ItemPatch.parse(changes, "folderId");
        return files.patch(id, user.id(), patch.hasName(), patch.name(), patch.hasDestination(), patch.destination());
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
}
