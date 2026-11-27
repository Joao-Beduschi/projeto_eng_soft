package com.cloudstorage.repository;

import com.cloudstorage.model.FolderRecord;
import com.cloudstorage.repository.entity.FolderEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FolderRepository extends JpaRepository<FolderEntity, UUID> {
    Optional<FolderEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Query("""
            select f from FolderEntity f
            where f.ownerId = :ownerId and f.parentId is not distinct from :parentId
            order by lower(f.name), f.createdAt, f.id
            """)
    List<FolderEntity> findChildren(@Param("ownerId") UUID ownerId, @Param("parentId") UUID parentId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FolderEntity f set f.name = :name, f.updatedAt = offset datetime
            where f.id = :id and f.ownerId = :ownerId
            """)
    int rename(@Param("id") UUID id, @Param("ownerId") UUID ownerId, @Param("name") String name);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update FolderEntity f set f.parentId = :parentId, f.updatedAt = offset datetime
            where f.id = :id and f.ownerId = :ownerId
            """)
    int move(@Param("id") UUID id, @Param("ownerId") UUID ownerId, @Param("parentId") UUID parentId);

    @Query(value = """
            WITH RECURSIVE descendants(id) AS (
                SELECT id FROM folders WHERE id = :folderId AND owner_id = :ownerId
                UNION
                SELECT child.id FROM folders child
                JOIN descendants parent ON child.parent_id = parent.id
                WHERE child.owner_id = :ownerId
            )
            SELECT EXISTS (SELECT 1 FROM descendants WHERE id = :possibleDescendantId)
            """, nativeQuery = true)
    boolean isSameOrDescendant(@Param("ownerId") UUID ownerId, @Param("folderId") UUID folderId,
                               @Param("possibleDescendantId") UUID possibleDescendantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            delete from FolderEntity folder
            where folder.id = :id and folder.ownerId = :ownerId
              and not exists (select 1 from FolderEntity child
                              where child.ownerId = :ownerId and child.parentId = folder.id)
              and not exists (select 1 from FileEntity file
                              where file.ownerId = :ownerId and file.folderId = folder.id)
            """)
    int deleteIfEmpty(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    default FolderRecord create(UUID id, UUID ownerId, UUID parentId, String name) {
        return saveAndFlush(new FolderEntity(new FolderRecord(id, ownerId, parentId, name, null, null))).snapshot();
    }

    default Optional<FolderRecord> findOwned(UUID id, UUID ownerId) {
        return findByIdAndOwnerId(id, ownerId).map(FolderEntity::snapshot);
    }

    default List<FolderRecord> list(UUID ownerId, UUID parentId) {
        return findChildren(ownerId, parentId).stream().map(FolderEntity::snapshot).toList();
    }
}
