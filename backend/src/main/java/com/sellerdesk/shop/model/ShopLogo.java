package com.sellerdesk.shop.model;

/**
 * The shop's logo, embedded in the shop document. The file itself is in the image storage
 * (Cloudinary).
 *
 * @param url      public HTTPS URL, sent to clients
 * @param publicId storage id, needed to delete the file. Never sent to clients.
 */
public record ShopLogo(String url, String publicId, int width, int height, long bytes, String format) {
}
