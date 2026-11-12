package com.cloudstorage.controller;

import com.cloudstorage.dto.FileResponse;
import com.cloudstorage.dto.FolderContentsResponse;
import com.cloudstorage.dto.FolderResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.UserPrincipal;
import com.cloudstorage.service.FileService;
import com.cloudstorage.service.FolderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/folders")
public class FolderController {
    private static final Set<String> PATCH_FIELDS = Set.of("name", "parentId");

    private final FolderService folders;
    private final FileService files;

    public FolderController(FolderService folders, FileService files) {
        this.folders = folders;
        this.files = files;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FolderResponse> create(@AuthenticationPrincipal UserPrincipal user,
                                                 @Valid @RequestBody CreateFolderRequest request) {
        FolderResponse created = folders.create(user.id(), request.parentId(), request.name());
        return ResponseEntity.created(URI.create("/api/folders/" + created.id())).body(created);
    }

    @GetMapping
    public List<FolderResponse> list(@AuthenticationPrincipal UserPrincipal user,
                                     @RequestParam(required = false) UUID parentId) {
        return folders.list(user.id(), parentId);
    }

    @GetMapping("/{id}")
    public FolderResponse get(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        return folders.get(id, user.id());
    }

    @GetMapping("/{id}/contents")
    public FolderContentsResponse contents(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        FolderResponse folder = folders.get(id, user.id());
        List<FolderResponse> childFolders = folders.list(user.id(), id);
        List<FileResponse> childFiles = files.list(user.id(), id);
        return new FolderContentsResponse(folder, childFolders, childFiles);
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FolderResponse patch(@AuthenticationPrincipal UserPrincipal user,
                                @PathVariable UUID id,
                                @RequestBody Map<String, Object> changes) {
        FileController.rejectUnknownFields(changes, PATCH_FIELDS);
        boolean hasName = changes.containsKey("name");
        String name = null;
        if (hasName) {
            Object rawName = changes.get("name");
            if (!(rawName instanceof String text)) {
                throw badPatch("name must be a string.");
            }
            name = text;
        }
        boolean hasParentId = changes.containsKey("parentId");
        UUID parentId = hasParentId ? FileController.parseUuidOrNull(changes.get("parentId"), "parentId") : null;
        return folders.patch(id, user.id(), hasName, name, hasParentId, parentId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        folders.delete(id, user.id());
        return ResponseEntity.noContent().build();
    }

    private static ApiException badPatch(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "invalid_patch", message);
    }

    public record CreateFolderRequest(
            @NotBlank @Size(max = 255) String name,
            UUID parentId) {
    }
}
