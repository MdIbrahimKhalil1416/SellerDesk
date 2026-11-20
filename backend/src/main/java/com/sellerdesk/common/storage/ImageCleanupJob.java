package com.sellerdesk.common.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every 15 minutes: deletes stored files whose cleanup task is due (orphan uploads older than
 * 1 h, removed images whose immediate delete failed). Runs outside any request: no TenantContext,
 * which is fine because it touches only {@link ImageCleanupTask}s.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCleanupJob {

    private final ImageCleanupTasks tasks;

    @Scheduled(fixedDelayString = "${sellerdesk.storage.cleanup-interval:PT15M}",
            initialDelayString = "${sellerdesk.storage.cleanup-interval:PT15M}")
    public void run() {
        int deleted = tasks.processDueTasks();
        if (deleted > 0) {
            log.info("Image cleanup deleted {} file(s)", deleted);
        }
    }
}
