package com.sellerdesk.product.service;

import com.sellerdesk.common.error.AppException;
import org.springframework.http.HttpStatus;

/** The file's bytes are not JPEG, PNG or WebP (or the file is empty) → {@code 400 IMAGE_TYPE_NOT_ALLOWED}. */
public class ImageTypeNotAllowedException extends AppException {

    public ImageTypeNotAllowedException() {
        super(HttpStatus.BAD_REQUEST, ProductErrors.IMAGE_TYPE_NOT_ALLOWED,
                "Only JPEG, PNG and WebP images are allowed");
    }
}
