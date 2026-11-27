package com.cloudstorage.repository;

import com.cloudstorage.repository.entity.EmailChangeToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface EmailChangeTokenRepository extends JpaRepository<EmailChangeToken, UUID> {
    @Query("""
            select t.newEmail from EmailChangeToken t
            where t.tokenHash = :hash and t.userId = :userId and t.credentialVersion = :version
              and t.user.credentialVersion = :version and t.usedAt is null and t.expiresAt > :now
            """)
    Optional<String> findActiveAddress(String hash, UUID userId, long version, Instant now);

    boolean existsByUserIdAndCreatedAtAfter(UUID userId, Instant cutoff);

    long countByUserIdAndCreatedAtAfter(UUID userId, Instant cutoff);

    @Modifying
    @Query("""
            update EmailChangeToken t set t.usedAt = :now
            where t.tokenHash = :hash and t.userId = :userId and t.credentialVersion = :version
              and t.usedAt is null and t.expiresAt > :now
            """)
    int consume(String hash, UUID userId, long version, Instant now);

    @Modifying
    @Query("update EmailChangeToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    void invalidateOutstanding(UUID userId, Instant now);

    @Transactional
    @Modifying
    @Query("""
            delete from EmailChangeToken t
            where t.createdAt <= :rateWindowCutoff and (t.usedAt is not null or t.expiresAt <= :now)
            """)
    void purgeObsolete(Instant now, Instant rateWindowCutoff);
}
