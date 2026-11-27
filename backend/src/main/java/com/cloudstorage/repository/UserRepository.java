package com.cloudstorage.repository;

import com.cloudstorage.model.UserAccount;
import com.cloudstorage.repository.entity.UserEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    @Query("select u from UserEntity u where lower(u.email) = :email")
    Optional<UserEntity> findByNormalizedEmail(@Param("email") String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id = :id")
    Optional<UserEntity> findForUpdate(@Param("id") UUID id);

    default UserAccount insert(UUID id, String email, String passwordHash) {
        return saveAndFlush(new UserEntity(new UserAccount(id, email, passwordHash, null))).snapshot();
    }

    default Optional<UserAccount> findByEmail(String normalizedEmail) {
        return findByNormalizedEmail(normalizedEmail).map(UserEntity::snapshot);
    }

    default Optional<UserAccount> findAccount(UUID id) {
        return findById(id).map(UserEntity::snapshot);
    }
}
