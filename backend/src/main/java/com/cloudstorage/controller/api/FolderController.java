package com.cloudstorage.controller.api;

import com.cloudstorage.dto.FolderContentsResponse;
import com.cloudstorage.dto.FolderResponse;
import com.cloudstorage.security.UserPrincipal;
import com.cloudstorage.service.FolderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final FolderService folders;

    @Autowired
    public FolderController(FolderService folders) {
        this.folders = folders;
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
        return folders.contents(id, user.id());
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public FolderResponse patch(@AuthenticationPrincipal UserPrincipal user,
                                @PathVariable UUID id,
                                @RequestBody Map<String, Object> changes) {
        ItemPatch patch = ItemPatch.parse(changes, "parentId");
        return folders.patch(id, user.id(), patch.hasName(), patch.name(), patch.hasDestination(), patch.destination());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        folders.delete(id, user.id());
        return ResponseEntity.noContent().build();
    }

    public record CreateFolderRequest(
            @NotBlank @Size(max = 255) String name,
            UUID parentId) {
    }
}
