package com.cloudstorage.service;

import com.cloudstorage.dto.FolderResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.FolderRecord;
import com.cloudstorage.repository.FolderRepository;
import com.cloudstorage.validation.NameValidator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FolderService {
    private final FolderRepository folders;

    public FolderService(FolderRepository folders) {
        this.folders = folders;
    }

    @Transactional
    public FolderResponse create(UUID ownerId, UUID parentId, String requestedName) {
        folders.lockOwner(ownerId);
        if (parentId != null && folders.findOwned(parentId, ownerId).isEmpty()) {
            throw notFound();
        }
        String name = NameValidator.validate(requestedName, "folder");
        return toResponse(folders.create(UUID.randomUUID(), ownerId, parentId, name));
    }

    public List<FolderResponse> list(UUID ownerId, UUID parentId) {
        if (parentId != null && folders.findOwned(parentId, ownerId).isEmpty()) {
            throw notFound();
        }
        return folders.list(ownerId, parentId).stream().map(FolderService::toResponse).toList();
    }

    public FolderResponse get(UUID id, UUID ownerId) {
        return toResponse(findOwned(id, ownerId));
    }

    @Transactional
    public FolderResponse patch(UUID id, UUID ownerId, boolean hasName, String requestedName,
                                boolean hasParentId, UUID parentId) {
        folders.lockOwner(ownerId);
        FolderRecord folder = findOwned(id, ownerId);
        if (!hasName && !hasParentId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_patch", "At least one supported field is required.");
        }

        if (hasName) {
            String name = NameValidator.validate(requestedName, "folder");
            if (!folders.rename(id, ownerId, name)) {
                throw notFound();
            }
        }

        if (hasParentId && !java.util.Objects.equals(folder.parentId(), parentId)) {
            if (parentId != null && folders.findOwned(parentId, ownerId).isEmpty()) {
                throw notFound();
            }
            if (parentId != null && folders.isSameOrDescendant(ownerId, id, parentId)) {
                throw new ApiException(HttpStatus.CONFLICT, "folder_cycle",
                        "A folder cannot be moved inside itself or one of its descendants.");
            }
            if (!folders.move(id, ownerId, parentId)) {
                throw notFound();
            }
        }
        return get(id, ownerId);
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        folders.lockOwner(ownerId);
        findOwned(id, ownerId);
        if (folders.hasChildrenOrFiles(id, ownerId)) {
            throw new ApiException(HttpStatus.CONFLICT, "folder_not_empty",
                    "A folder must be empty before it can be deleted.");
        }
        if (!folders.deleteIfEmpty(id, ownerId)) {
            throw new ApiException(HttpStatus.CONFLICT, "folder_not_empty",
                    "A folder must be empty before it can be deleted.");
        }
    }

    public static FolderResponse toResponse(FolderRecord folder) {
        return new FolderResponse(folder.id(), folder.name(), folder.parentId(),
                folder.createdAt(), folder.updatedAt());
    }

    private FolderRecord findOwned(UUID id, UUID ownerId) {
        return folders.findOwned(id, ownerId).orElseThrow(this::notFound);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "folder_not_found", "The requested folder was not found.");
    }
}
