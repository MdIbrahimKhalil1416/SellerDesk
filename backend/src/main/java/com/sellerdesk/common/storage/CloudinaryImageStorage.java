package com.sellerdesk.common.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.Transformation;
import com.cloudinary.utils.ObjectUtils;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link ImageStorage} backed by Cloudinary (docs/modules/PRODUCT.md §4).
 *
 * <p>Cloudinary also checks the file: only {@code jpg, png, webp} are accepted, and images larger
 * than 2000 × 2000 px are scaled down on arrival ({@code c_limit}), so a 12-megapixel phone photo
 * does not make every product page slow.
 *
 * <p>Creating the {@link Cloudinary} client only parses the URL; no network call happens at
 * startup. Integration tests replace this bean with a fake.
 */
@Slf4j
@Component
public class CloudinaryImageStorage implements ImageStorage {

    static final String ALLOWED_FORMATS = "jpg,png,webp";

    /** {@code null} when no URL is configured (dev without Cloudinary). */
    private final Cloudinary cloudinary;

    public CloudinaryImageStorage(CloudinaryProperties properties) {
        String url = properties.url();
        if (url == null || url.isBlank()) {
            if (properties.required()) {
                throw new IllegalStateException("CLOUDINARY_URL is not set (sellerdesk.cloudinary.url)");
            }
            log.warn("CLOUDINARY_URL is not set: image uploads will fail with 502 IMAGE_UPLOAD_FAILED");
            this.cloudinary = null;
        } else {
            this.cloudinary = new Cloudinary(url);
            this.cloudinary.config.secure = true;
        }
    }

    @Override
    public StoredImage upload(byte[] bytes, String publicId) {
        Cloudinary client = requireClient();
        try {
            Map<?, ?> result = client.uploader().upload(bytes, ObjectUtils.asMap(
                    "public_id", publicId,
                    "overwrite", false,
                    "resource_type", "image",
                    "allowed_formats", ALLOWED_FORMATS,
                    "transformation", new Transformation<>().crop("limit").width(2000).height(2000)));
            return new StoredImage(
                    (String) result.get("secure_url"),
                    (String) result.get("public_id"),
                    toInt(result.get("width")),
                    toInt(result.get("height")),
                    toLong(result.get("bytes")),
                    (String) result.get("format"));
        } catch (Exception e) {
            // Never log the Cloudinary URL: it contains the API secret.
            throw new ImageStorageException("Cloudinary upload failed for " + publicId, e);
        }
    }

    @Override
    public void delete(String publicId) {
        Cloudinary client = requireClient();
        Map<?, ?> result;
        try {
            result = client.uploader().destroy(publicId, ObjectUtils.asMap("invalidate", true));
        } catch (Exception e) {
            throw new ImageStorageException("Cloudinary delete failed for " + publicId, e);
        }
        Object outcome = result.get("result");
        // "not found" = already gone. Deleting is idempotent, so that is a success too.
        if (!"ok".equals(outcome) && !"not found".equals(outcome)) {
            throw new ImageStorageException("Cloudinary delete of " + publicId + " returned " + outcome);
        }
    }

    private Cloudinary requireClient() {
        if (cloudinary == null) {
            throw new ImageStorageException("Cloudinary is not configured (CLOUDINARY_URL)");
        }
        return cloudinary;
    }

    private static int toInt(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
