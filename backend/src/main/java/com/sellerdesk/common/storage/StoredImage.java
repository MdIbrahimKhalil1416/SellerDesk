package com.sellerdesk.common.storage;

/**
 * What the storage returns after an upload.
 *
 * @param url      public HTTPS URL of the image
 * @param publicId the provider's id, needed to delete the file later (never sent to clients)
 * @param bytes    size of the stored file
 * @param format   e.g. {@code jpg}, {@code png}, {@code webp}
 */
public record StoredImage(String url, String publicId, int width, int height, long bytes, String format) {
}
