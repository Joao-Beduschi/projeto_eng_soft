package com.cloudstorage.repository;

import com.cloudstorage.model.FileRecord;
import com.cloudstorage.repository.entity.FileEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FileRepository extends JpaRepository<FileEntity, UUID> {
    Optional<FileEntity> findByIdAndOwnerIdAndStatus(UUID id, UUID ownerId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FileEntity f where f.id = :id and f.ownerId = :ownerId and f.status = 'ready'")
    Optional<FileEntity> lockReadyOwned(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    @Query("""
            select f from FileEntity f
            where f.ownerId = :ownerId and f.folderId is not distinct from :folderId and f.status = 'ready'
            order by lower(f.name), f.createdAt, f.id
            """)
    List<FileEntity> findReadyInFolder(@Param("ownerId") UUID ownerId, @Param("folderId") UUID folderId);

    List<FileEntity> findByStatusNotOrderByCreatedAt(String status);

    @Query("select f.storageKey from FileEntity f")
    Set<UUID> findAllStorageKeys();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FileEntity f set f.status = 'ready', f.sha256 = :sha256, f.mimeType = :mimeType,
                f.currentVersionId = :currentVersionId, f.updatedAt = offset datetime
            where f.id = :id and f.ownerId = :ownerId and f.status = 'pending'
            """)
    int markReady(@Param("id") UUID id, @Param("ownerId") UUID ownerId,
                  @Param("currentVersionId") UUID currentVersionId, @Param("sha256") String sha256,
                  @Param("mimeType") String mimeType);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FileEntity f set f.currentVersionId = :nextVersionId, f.storageKey = :storageKey,
                f.sizeBytes = :sizeBytes, f.sha256 = :sha256, f.mimeType = :mimeType, f.updatedAt = offset datetime
            where f.id = :id and f.ownerId = :ownerId and f.status = 'ready'
              and f.currentVersionId = :expectedVersionId
            """)
    int advanceCurrentVersion(@Param("id") UUID id, @Param("ownerId") UUID ownerId,
                              @Param("expectedVersionId") UUID expectedVersionId,
                              @Param("nextVersionId") UUID nextVersionId, @Param("storageKey") UUID storageKey,
                              @Param("sizeBytes") long sizeBytes, @Param("sha256") String sha256,
                              @Param("mimeType") String mimeType);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FileEntity f set f.name = :name, f.updatedAt = offset datetime
            where f.id = :id and f.ownerId = :ownerId and f.status = 'ready'
            """)
    int renameReady(@Param("id") UUID id, @Param("ownerId") UUID ownerId, @Param("name") String name);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FileEntity f set f.folderId = :folderId, f.updatedAt = offset datetime
            where f.id = :id and f.ownerId = :ownerId and f.status = 'ready'
            """)
    int moveReady(@Param("id") UUID id, @Param("ownerId") UUID ownerId, @Param("folderId") UUID folderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FileEntity f set f.status = 'deleting', f.currentVersionId = null, f.updatedAt = offset datetime
            where f.id = :id and f.ownerId = :ownerId and f.status = 'ready'
            """)
    int transitionToDeleting(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from FileEntity f where f.id = :id and f.status = :status")
    int deleteWithStatus(@Param("id") UUID id, @Param("status") String status);

    default void createPending(FileRecord file) {
        saveAndFlush(new FileEntity(file));
    }

    default Optional<FileRecord> findReadyOwned(UUID id, UUID ownerId) {
        return findByIdAndOwnerIdAndStatus(id, ownerId, "ready").map(FileEntity::snapshot);
    }

    default Optional<FileRecord> findReadyOwnedForUpdate(UUID id, UUID ownerId) {
        return lockReadyOwned(id, ownerId).map(FileEntity::snapshot);
    }

    default List<FileRecord> listReady(UUID ownerId, UUID folderId) {
        return findReadyInFolder(ownerId, folderId).stream().map(FileEntity::snapshot).toList();
    }

    @Transactional
    default Optional<FileRecord> markDeleting(UUID id, UUID ownerId) {
        if (transitionToDeleting(id, ownerId) != 1) {
            return Optional.empty();
        }
        return findByIdAndOwnerIdAndStatus(id, ownerId, "deleting").map(FileEntity::snapshot);
    }

    default List<FileRecord> findNonReady() {
        return findByStatusNotOrderByCreatedAt("ready").stream().map(FileEntity::snapshot).toList();
    }
}
