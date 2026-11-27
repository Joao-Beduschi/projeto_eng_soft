package com.cloudstorage.service;

import com.cloudstorage.dto.FolderContentsResponse;
import com.cloudstorage.dto.FolderResponse;
import com.cloudstorage.exception.ApiException;
import com.cloudstorage.model.FolderRecord;
import com.cloudstorage.repository.FolderRepository;
import com.cloudstorage.repository.UserRepository;
import com.cloudstorage.validation.NameValidator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FolderService {
    private final FolderRepository folders;

    private final FileService files;
    private final UserRepository users;

    @Autowired
    public FolderService(FolderRepository folders, FileService files, UserRepository users) {
        this.folders = folders;
        this.files = files;
        this.users = users;
    }

    @Transactional
    public FolderResponse create(UUID ownerId, UUID parentId, String requestedName) {
        lockOwner(ownerId);
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

    @Transactional(readOnly = true)
    public FolderContentsResponse contents(UUID id, UUID ownerId) {
        FolderResponse folder = get(id, ownerId);
        return new FolderContentsResponse(folder, list(ownerId, id), files.list(ownerId, id));
    }

    public FolderResponse get(UUID id, UUID ownerId) {
        return toResponse(findOwned(id, ownerId));
    }

    @Transactional
    public FolderResponse patch(UUID id, UUID ownerId, boolean hasName, String requestedName,
                                boolean hasParentId, UUID parentId) {
        lockOwner(ownerId);
        FolderRecord folder = findOwned(id, ownerId);
        if (!hasName && !hasParentId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "empty_patch");
        }

        if (hasName) {
            String name = NameValidator.validate(requestedName, "folder");
            if (folders.rename(id, ownerId, name) != 1) {
                throw notFound();
            }
        }

        if (hasParentId && !java.util.Objects.equals(folder.parentId(), parentId)) {
            if (parentId != null && folders.findOwned(parentId, ownerId).isEmpty()) {
                throw notFound();
            }
            if (parentId != null && folders.isSameOrDescendant(ownerId, id, parentId)) {
                throw new ApiException(HttpStatus.CONFLICT, "folder_cycle");
            }
            if (folders.move(id, ownerId, parentId) != 1) {
                throw notFound();
            }
        }
        return get(id, ownerId);
    }

    @Transactional
    public void delete(UUID id, UUID ownerId) {
        lockOwner(ownerId);
        findOwned(id, ownerId);
        if (folders.deleteIfEmpty(id, ownerId) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "folder_not_empty");
        }
    }

    public static FolderResponse toResponse(FolderRecord folder) {
        return new FolderResponse(folder.id(), folder.name(), folder.parentId(),
                folder.createdAt(), folder.updatedAt());
    }

    private void lockOwner(UUID ownerId) {
        users.findForUpdate(ownerId).orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
    }

    private FolderRecord findOwned(UUID id, UUID ownerId) {
        return folders.findOwned(id, ownerId).orElseThrow(this::notFound);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "folder_not_found");
    }
}
