package com.cloudstorage.repository;

import com.cloudstorage.model.FileVersionRecord;
import com.cloudstorage.repository.entity.FileVersionEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FileVersionRepository extends JpaRepository<FileVersionEntity, UUID> {
    String READY_OWNED = """
            from FileVersionEntity v join v.file f
            where f.id = :fileId and f.ownerId = :ownerId and f.status = 'ready' and v.status = 'ready'
            """;

    @Query("select v " + READY_OWNED + " and v.id = f.currentVersionId")
    Optional<FileVersionEntity> findCurrent(@Param("fileId") UUID fileId, @Param("ownerId") UUID ownerId);

    @Query("select v " + READY_OWNED + " order by v.versionNumber")
    List<FileVersionEntity> findHistory(@Param("fileId") UUID fileId, @Param("ownerId") UUID ownerId);

    @Query("select v " + READY_OWNED + " and v.versionNumber = :versionNumber")
    Optional<FileVersionEntity> findRevision(@Param("fileId") UUID fileId, @Param("versionNumber") int versionNumber,
                                             @Param("ownerId") UUID ownerId);

    List<FileVersionEntity> findByStatusAndCreatedAtBeforeOrderByCreatedAt(String status, Instant cutoff);

    @Query("select v.storageKey from FileVersionEntity v where v.fileId = :fileId")
    Set<UUID> findStorageKeysForFile(@Param("fileId") UUID fileId);

    @Query("select v.storageKey from FileVersionEntity v")
    Set<UUID> findAllStorageKeys();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FileVersionEntity v set v.status = 'ready', v.sizeBytes = :sizeBytes, v.sha256 = :sha256
            where v.id = :id and v.fileId = :fileId and v.status = 'pending'
            """)
    int markReady(@Param("id") UUID id, @Param("fileId") UUID fileId, @Param("sizeBytes") long sizeBytes,
                  @Param("sha256") String sha256);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from FileVersionEntity v where v.id = :id and v.status = 'pending'")
    int deletePending(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from FileVersionEntity v where v.fileId = :fileId")
    int deleteAllForFile(@Param("fileId") UUID fileId);

    default FileVersionRecord createPending(FileVersionRecord version) {
        return saveAndFlush(new FileVersionEntity(version)).snapshot();
    }

    default Optional<FileVersionRecord> findCurrentReadyOwned(UUID fileId, UUID ownerId) {
        return findCurrent(fileId, ownerId).map(FileVersionEntity::snapshot);
    }

    default List<FileVersionRecord> listReadyOwned(UUID fileId, UUID ownerId) {
        return findHistory(fileId, ownerId).stream().map(FileVersionEntity::snapshot).toList();
    }

    default Optional<FileVersionRecord> findReadyOwned(UUID fileId, int versionNumber, UUID ownerId) {
        return findRevision(fileId, versionNumber, ownerId).map(FileVersionEntity::snapshot);
    }

    default List<FileVersionRecord> findStalePending(Instant cutoff) {
        return findByStatusAndCreatedAtBeforeOrderByCreatedAt("pending", cutoff)
                .stream().map(FileVersionEntity::snapshot).toList();
    }
}
