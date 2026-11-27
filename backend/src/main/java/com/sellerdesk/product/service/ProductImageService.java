package com.sellerdesk.product.service;

import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.storage.ImageCleanupTasks;
import com.sellerdesk.common.storage.ImageStorage;
import com.sellerdesk.common.storage.ImageStorageException;
import com.sellerdesk.common.storage.ImageUploadFailedException;
import com.sellerdesk.common.storage.ImageValidator;
import com.sellerdesk.common.storage.ImagesRemovedEvent;
import com.sellerdesk.common.storage.StoredImage;
import com.sellerdesk.product.dto.ProductResponse;
import com.sellerdesk.product.dto.ReorderImagesRequest;
import com.sellerdesk.product.model.Product;
import com.sellerdesk.product.model.ProductImage;
import com.sellerdesk.product.repository.ProductQueryRepository;
import com.sellerdesk.product.repository.ProductRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Product images: upload, remove, reorder (docs/modules/PRODUCT.md §4).
 *
 * <h2>Upload — and what if the upload succeeds but saving fails?</h2>
 * The upload to Cloudinary and the database write cannot be one transaction. So:
 * <ol>
 *   <li>Write a cleanup task for the file <b>before</b> uploading ("delete in 1 h").</li>
 *   <li>Upload (no database lock is held during the network call).</li>
 *   <li>Save the image on the product with one atomic {@code $push}.</li>
 *   <li>Saved → cancel the task. Not saved → delete the file right away; if even that fails,
 *       or the server crashes anywhere in between, the task makes the cleanup job delete it.</li>
 * </ol>
 * So a product never points at a missing file, and an orphan file lives about an hour at most.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageService {

    private final ProductRepository productRepository;
    private final ProductQueryRepository queryRepository;
    private final ProductMapper mapper;
    private final CurrentUser currentUser;
    private final ImageValidator imageValidator;
    private final ImageStorage imageStorage;
    private final ImageCleanupTasks cleanupTasks;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ProductResponse upload(String productId, MultipartFile file) {
        String shopId = currentUser.shopId();
        Product product = load(shopId, productId);
        if (product.getImages().size() >= ProductRules.MAX_IMAGES) {
            throw ProductErrors.imageLimitReached();
        }
        byte[] bytes = readBytes(file);
        imageValidator.validate(bytes);

        String imageId = new ObjectId().toHexString();
        String publicId = "sellerdesk/" + shopId + "/products/" + imageId;
        String taskId = cleanupTasks.scheduleOrphanCheck(shopId, publicId);

        StoredImage stored;
        try {
            stored = imageStorage.upload(bytes, publicId);
        } catch (ImageStorageException e) {
            log.warn("Image upload for product {} failed: {}", productId, e.getMessage());
            cleanupTasks.cancel(taskId); // nothing was stored
            throw new ImageUploadFailedException(e);
        }

        ProductImage image = new ProductImage();
        image.setImageId(imageId);
        image.setUrl(stored.url());
        image.setPublicId(publicId);
        image.setWidth(stored.width());
        image.setHeight(stored.height());
        image.setBytes(stored.bytes());
        image.setFormat(stored.format());

        // Atomic: only if the product still has fewer than 8 images ("images.7" missing), so
        // parallel uploads can never exceed the limit.
        Optional<Product> saved;
        try {
            saved = queryRepository.modify(shopId, productId,
                    Criteria.where("images." + (ProductRules.MAX_IMAGES - 1)).exists(false),
                    new Update().push("images", image).set("updatedAt", Instant.now(clock)).inc("version", 1));
        } catch (RuntimeException databaseError) {
            // The database is failing: delete the file now, but keep the task (writing to the
            // database may fail too). The job later finds the file gone, which counts as done.
            tryDeleteFile(publicId);
            throw databaseError;
        }

        if (saved.isEmpty()) {
            // Product deleted meanwhile, or a parallel upload took the last slot.
            if (tryDeleteFile(publicId)) {
                cleanupTasks.cancel(taskId);
            }
            throw productRepository.existsByIdAndShopId(productId, shopId)
                    ? ProductErrors.imageLimitReached()
                    : ProductErrors.productNotFound();
        }
        cleanupTasks.cancel(taskId);
        events.publishEvent(new ProductChangedEvent(shopId, productId));
        return mapper.toResponse(saved.get());
    }

    /**
     * Removes the image from the product and deletes the file. The {@code $pull} and the
     * cleanup task are written in one transaction; the file is deleted after commit
     * ({@code ImageCleanupListener}), or later by the job if Cloudinary is unavailable.
     */
    @Transactional
    public ProductResponse delete(String productId, String imageId) {
        String shopId = currentUser.shopId();
        Product product = load(shopId, productId);
        ProductImage image = findImage(product, imageId);

        Product updated = queryRepository.modify(shopId, productId,
                        Criteria.where("images.imageId").is(new ObjectId(imageId)),
                        new Update().pull("images", new Document("imageId", new ObjectId(imageId)))
                                .set("updatedAt", Instant.now(clock))
                                .inc("version", 1))
                .orElseThrow(() -> productRepository.existsByIdAndShopId(productId, shopId)
                        ? ProductErrors.imageNotFound()
                        : ProductErrors.productNotFound());

        cleanupTasks.scheduleRemoval(shopId, List.of(image.getPublicId()));
        events.publishEvent(new ImagesRemovedEvent(shopId, List.of(image.getPublicId())));
        events.publishEvent(new ProductChangedEvent(shopId, productId));
        return mapper.toResponse(updated);
    }

    /** New order of the same images; the first becomes the cover. */
    public ProductResponse reorder(String productId, ReorderImagesRequest request) {
        String shopId = currentUser.shopId();
        Product product = load(shopId, productId);
        if (!Objects.equals(product.getVersion(), request.version())) {
            throw ProductErrors.staleVersion();
        }
        Map<String, ProductImage> byId = product.getImages().stream()
                .collect(Collectors.toMap(ProductImage::getImageId, Function.identity()));
        List<String> ids = request.imageIds();
        boolean sameSet = ids.size() == byId.size()
                && new HashSet<>(ids).size() == ids.size()
                && byId.keySet().containsAll(ids);
        if (!sameSet) {
            throw new FieldValidationException("imageIds", "must list every image of the product exactly once");
        }
        List<ProductImage> ordered = ids.stream().map(byId::get).toList();

        Product updated = queryRepository.modify(shopId, productId,
                        Criteria.where("version").is(request.version()),
                        new Update().set("images", ordered).set("updatedAt", Instant.now(clock)).inc("version", 1))
                .orElseThrow(() -> productRepository.existsByIdAndShopId(productId, shopId)
                        ? ProductErrors.staleVersion()
                        : ProductErrors.productNotFound());
        events.publishEvent(new ProductChangedEvent(shopId, productId));
        return mapper.toResponse(updated);
    }

    // ---- helpers ----------------------------------------------------------------------

    private Product load(String shopId, String productId) {
        return productRepository.findByIdAndShopId(productId, shopId)
                .orElseThrow(ProductErrors::productNotFound);
    }

    private static ProductImage findImage(Product product, String imageId) {
        if (!ObjectId.isValid(imageId)) {
            throw ProductErrors.imageNotFound();
        }
        return product.getImages().stream()
                .filter(i -> i.getImageId().equals(imageId))
                .findFirst()
                .orElseThrow(ProductErrors::imageNotFound);
    }

    /** @return true if the file is gone */
    private boolean tryDeleteFile(String publicId) {
        try {
            imageStorage.delete(publicId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not delete unsaved upload {} (the cleanup job will): {}", publicId, e.getMessage());
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
