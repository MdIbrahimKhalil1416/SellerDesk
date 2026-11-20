package com.sellerdesk.common.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Deletes removed images right away instead of waiting for the next {@link ImageCleanupJob} run.
 *
 * <ul>
 *   <li>{@code AFTER_COMMIT}: only once the image is really gone from the product. If the
 *       transaction rolled back, the product still shows the image, so the file must stay.</li>
 *   <li>{@code fallbackExecution = true}: also runs when the event was published without a
 *       transaction.</li>
 *   <li>{@code @Async}: the HTTP response does not wait for Cloudinary.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageCleanupListener {

    private final ImageCleanupTasks tasks;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onImagesRemoved(ImagesRemovedEvent event) {
        for (String publicId : event.publicIds()) {
            try {
                tasks.deleteNow(publicId);
            } catch (RuntimeException e) {
                // The task stays; the job retries later.
                log.warn("Immediate delete of image {} (shop {}) failed: {}", publicId, event.shopId(), e.getMessage());
            }
        }
    }
}
