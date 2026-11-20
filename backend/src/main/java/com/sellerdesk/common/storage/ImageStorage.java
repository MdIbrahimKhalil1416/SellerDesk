package com.sellerdesk.common.storage;

/**
 * Where product images are stored. Production uses {@link CloudinaryImageStorage}; tests use a
 * fake that records calls and never touches the network.
 *
 * <p>Why an interface: services depend on "something that can store an image", not on
 * Cloudinary. Tests can swap it, and changing the provider later only touches one class.
 */
public interface ImageStorage {

    /**
     * Stores the image under {@code publicId} (we choose the id, so a cleanup task can be written
     * <b>before</b> the upload, see docs/modules/PRODUCT.md §4).
     *
     * @param bytes    already validated image bytes (JPEG, PNG or WebP)
     * @param publicId e.g. {@code sellerdesk/<shopId>/products/<imageId>}
     * @throws ImageStorageException if the upload failed
     */
    StoredImage upload(byte[] bytes, String publicId);

    /**
     * Deletes the file. Idempotent: a file that does not exist (anymore) counts as success.
     *
     * @throws ImageStorageException if the provider could not be reached or refused
     */
    void delete(String publicId);
}
