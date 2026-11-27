package com.sellerdesk.shop.service;

import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.storage.ImageCleanupTasks;
import com.sellerdesk.common.storage.ImageStorage;
import com.sellerdesk.common.storage.ImageStorageException;
import com.sellerdesk.common.storage.ImageUploadFailedException;
import com.sellerdesk.common.storage.ImageValidator;
import com.sellerdesk.common.storage.ImagesRemovedEvent;
import com.sellerdesk.common.storage.StoredImage;
import com.sellerdesk.shop.dto.ShopResponse;
import com.sellerdesk.shop.model.Shop;
import com.sellerdesk.shop.model.ShopLogo;
import com.sellerdesk.shop.repository.ShopQueryRepository;
import com.sellerdesk.shop.repository.ShopRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * The shop logo: same image pipeline as product images (docs/modules/PRODUCT.md §4).
 *
 * <ul>
 *   <li>Same checks: JPEG/PNG/WebP by magic bytes, max 5 MB ({@link ImageValidator}).</li>
 *   <li>Same safety net: a cleanup task is written <b>before</b> the upload, so a file whose save
 *       failed (or a server crash in between) is deleted by the cleanup job later.</li>
 *   <li>Replacing: the old file is deleted. Each upload gets a new storage id, so browsers and
 *       the CDN never show a cached old logo under the new URL.</li>
 * </ul>
 *
 * <p>The logo is not part of the cached settings ({@link ShopSettingsLookup}), so no cache eviction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopLogoService {

    private final ShopRepository shopRepository;
    private final ShopQueryRepository shopQueryRepository;
    private final CurrentUser currentUser;
    private final ImageValidator imageValidator;
    private final ImageStorage imageStorage;
    private final ImageCleanupTasks cleanupTasks;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /** Sets or replaces the logo. Returns the shop with the new {@code logoUrl}. */
    public ShopResponse upload(MultipartFile file) {
        String shopId = currentUser.shopId();
        if (!shopRepository.existsById(shopId)) {
            throw ShopService.shopNotFound();
        }
        byte[] bytes = readBytes(file);
        imageValidator.validate(bytes);

        String publicId = "sellerdesk/" + shopId + "/logo/" + new ObjectId().toHexString();
        String taskId = cleanupTasks.scheduleOrphanCheck(shopId, publicId);

        StoredImage stored;
        try {
            stored = imageStorage.upload(bytes, publicId);
        } catch (ImageStorageException e) {
            log.warn("Logo upload for shop {} failed: {}", shopId, e.getMessage());
            cleanupTasks.cancel(taskId); // nothing was stored
            throw new ImageUploadFailedException(e);
        }
        ShopLogo logo = new ShopLogo(stored.url(), publicId, stored.width(), stored.height(), stored.bytes(),
                stored.format());

        // One transaction: set the new logo, keep its file (cancel the orphan task) and schedule
        // the old file's deletion. Either all of it happens or none of it.
        Shop previous;
        try {
            previous = transactions.execute(tx -> {
                Shop before = shopQueryRepository
                        .updateAndGetPrevious(shopId, new Update().set("logo", logo), Instant.now(clock))
                        .orElse(null);
                if (before != null) {
                    cleanupTasks.cancel(taskId);
                    scheduleRemoval(shopId, before.getLogo());
                }
                return before;
            });
        } catch (RuntimeException databaseError) {
            // Delete the new file now, but keep its task (writing to the database may fail too).
            tryDeleteFile(publicId);
            throw databaseError;
        }
        if (previous == null) {
            if (tryDeleteFile(publicId)) {
                cleanupTasks.cancel(taskId);
            }
            throw ShopService.shopNotFound();
        }
        return currentShop(shopId);
    }

    /** Removes the logo and deletes its file. No logo: nothing changes, still {@code 200}. */
    public ShopResponse delete() {
        String shopId = currentUser.shopId();
        Shop shop = shopRepository.findById(shopId).orElseThrow(ShopService::shopNotFound);
        if (shop.getLogo() == null) {
            return ShopResponse.from(shop);
        }
        transactions.executeWithoutResult(tx -> {
            Shop before = shopQueryRepository
                    .updateAndGetPrevious(shopId, new Update().unset("logo"), Instant.now(clock))
                    .orElseThrow(ShopService::shopNotFound);
            scheduleRemoval(shopId, before.getLogo());
        });
        return currentShop(shopId);
    }

    /**
     * The file of a replaced or removed logo must go: a {@code REMOVED} task (written in the
     * caller's transaction) plus an event, so {@code ImageCleanupListener} deletes it right after
     * the commit. If Cloudinary is down, the cleanup job retries later.
     */
    private void scheduleRemoval(String shopId, ShopLogo oldLogo) {
        if (oldLogo == null) {
            return;
        }
        List<String> ids = List.of(oldLogo.publicId());
        cleanupTasks.scheduleRemoval(shopId, ids);
        events.publishEvent(new ImagesRemovedEvent(shopId, ids));
    }

    private ShopResponse currentShop(String shopId) {
        return shopRepository.findById(shopId).map(ShopResponse::from).orElseThrow(ShopService::shopNotFound);
    }

    /** @return true if the file is gone */
    private boolean tryDeleteFile(String publicId) {
        try {
            imageStorage.delete(publicId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not delete unsaved logo {} (the cleanup job will): {}", publicId, e.getMessage());
            return false;
        }
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file == null ? new byte[0] : file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded file", e);
        }
    }
}
