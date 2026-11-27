package com.sellerdesk.common.storage;

import com.sellerdesk.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * The file's bytes are not JPEG, PNG or WebP (or the file is empty) → {@code 400 IMAGE_TYPE_NOT_ALLOWED}.
 * Shared by every image upload (product images, shop logo).
 */
public class ImageTypeNotAllowedException extends AppException {

    public static final String CODE = "IMAGE_TYPE_NOT_ALLOWED";

    public ImageTypeNotAllowedException() {
        super(HttpStatus.BAD_REQUEST, CODE, "Only JPEG, PNG and WebP images are allowed");
    }
}
