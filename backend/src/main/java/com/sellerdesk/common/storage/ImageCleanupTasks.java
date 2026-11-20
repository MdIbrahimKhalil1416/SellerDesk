package com.sellerdesk.common.storage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Bookkeeping for files that may have to be deleted from the image storage
 * (docs/modules/PRODUCT.md §4). Guarantees that an orphan file is removed eventually, even if
 * the server crashes in the middle of an upload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageCleanupTasks {

    /** An upload gets this long to be saved on its product before its file counts as an orphan. */
    static final Duration ORPHAN_GRACE = Duration.ofHours(1);
    static final int MAX_ATTEMPTS = 10;
    static final int BATCH_SIZE = 100;

    private final ImageCleanupTaskRepository repository;
    private final ImageStorage imageStorage;
    private final Clock clock;

    /** Before an upload: "delete this file in 1 h unless the task is cancelled". Returns the task id. */
    public String scheduleOrphanCheck(String shopId, String publicId) {
        return repository.save(newTask(shopId, publicId, ImageCleanupReason.ORPHAN_UPLOAD,
                Instant.now(clock).plus(ORPHAN_GRACE))).getId();
    }

    /** Files that are no longer referenced: due immediately. */
    public void scheduleRemoval(String shopId, Collection<String> publicIds) {
        Instant now = Instant.now(clock);
        publicIds.forEach(id -> repository.save(newTask(shopId, id, ImageCleanupReason.REMOVED, now)));
    }

    /** The upload was saved (or its file was already deleted): nothing to clean up. */
    public void cancel(String taskId) {
        repository.deleteById(taskId);
    }

    /** Delete the file now and forget its tasks. Throws if the storage fails; the tasks then stay. */
    public void deleteNow(String publicId) {
        imageStorage.delete(publicId);
        repository.deleteByPublicId(publicId);
    }

    /**
     * One run of the cleanup job: deletes the files of all due tasks. Failures are retried with
     * exponential backoff (2, 4, 8... minutes); after {@value #MAX_ATTEMPTS} failed attempts the
     * task is dropped and an error is logged with the file id, for manual cleanup.
     *
     * @return number of files deleted
     */
    public int processDueTasks() {
        Instant now = Instant.now(clock);
        List<ImageCleanupTask> due =
                repository.findByNotBeforeLessThanEqualOrderByNotBeforeAsc(now, Limit.of(BATCH_SIZE));
        int deleted = 0;
        for (ImageCleanupTask task : due) {
            try {
                imageStorage.delete(task.getPublicId());
                repository.deleteById(task.getId());
                deleted++;
            } catch (RuntimeException e) {
                handleFailure(task, now, e);
            }
        }
        return deleted;
    }

    private void handleFailure(ImageCleanupTask task, Instant now, RuntimeException e) {
        int attempts = task.getAttempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            log.error("Giving up deleting image {} (shop {}) after {} attempts; delete it by hand",
                    task.getPublicId(), task.getShopId(), attempts, e);
            repository.deleteById(task.getId());
            return;
        }
        log.warn("Deleting image {} failed (attempt {}): {}", task.getPublicId(), attempts, e.getMessage());
        task.setAttempts(attempts);
        task.setNotBefore(now.plus(Duration.ofMinutes(1L << attempts)));
        repository.save(task);
    }

    private ImageCleanupTask newTask(String shopId, String publicId, ImageCleanupReason reason, Instant notBefore) {
        ImageCleanupTask task = new ImageCleanupTask();
        task.setShopId(shopId);
        task.setPublicId(publicId);
        task.setReason(reason);
        task.setAttempts(0);
        task.setNotBefore(notBefore);
        task.setCreatedAt(Instant.now(clock));
        return task;
    }
}
