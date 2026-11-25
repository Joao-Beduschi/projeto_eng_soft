package com.cloudstorage.service;

import com.cloudstorage.model.FileRecord;
import com.cloudstorage.model.FileVersionRecord;
import com.cloudstorage.repository.FileRepository;
import com.cloudstorage.repository.FileVersionRepository;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FileReconciliationService implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(FileReconciliationService.class);
    private final FileRepository files;
    private final FileVersionRepository versions;
    private final StorageService storage;

    public FileReconciliationService(FileRepository files, FileVersionRepository versions,
                                     StorageService storage) {
        this.files = files;
        this.versions = versions;
        this.storage = storage;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant cutoffInstant = Instant.now().minusSeconds(60 * 60);
        try {
            for (FileRecord record : files.findNonReady()) {
                boolean stalePending = record.status().equals("pending")
                        && record.createdAt().toInstant().isBefore(cutoffInstant);
                if (!record.status().equals("deleting") && !stalePending) {
                    continue;
                }
                try {
                    Set<UUID> keys = new HashSet<>(versions.findStorageKeysForFile(record.id()));
                    keys.add(record.storageKey());
                    for (UUID key : keys) {
                        storage.delete(key);
                    }
                    versions.deleteAllForFile(record.id());
                    files.deleteWithStatus(record.id(), record.status());
                } catch (IOException | RuntimeException exception) {
                    log.warn("Could not reconcile incomplete file metadata {}", record.id(), exception);
                }
            }

            for (FileVersionRecord pending : versions.findStalePending(
                    OffsetDateTime.ofInstant(cutoffInstant, java.time.ZoneOffset.UTC))) {
                try {
                    storage.delete(pending.storageKey());
                    versions.deletePending(pending.id());
                } catch (IOException | RuntimeException exception) {
                    log.warn("Could not reconcile pending file revision {}", pending.id(), exception);
                }
            }

            Set<UUID> referenced = new HashSet<>(files.findAllStorageKeys());
            referenced.addAll(versions.findAllStorageKeys());
            for (UUID storedKey : storage.listStoredKeys()) {
                if (!referenced.contains(storedKey)) {
                    try {
                        storage.delete(storedKey);
                    } catch (IOException exception) {
                        log.warn("Could not remove an orphaned storage object", exception);
                    }
                }
            }
            storage.removeExpiredTemporaryFiles(cutoffInstant);
        } catch (IOException | RuntimeException exception) {
            log.error("File storage reconciliation could not finish", exception);
        }
    }
}
