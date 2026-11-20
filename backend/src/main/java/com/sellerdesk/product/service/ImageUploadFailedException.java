package com.sellerdesk.product.service;

import com.sellerdesk.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * The image storage (Cloudinary) did not accept the upload → {@code 502 IMAGE_UPLOAD_FAILED}.
 * 502 "Bad Gateway" = our server is fine, but a service behind it failed; the client may retry.
 */
public class ImageUploadFailedException extends AppException {

    public ImageUploadFailedException(Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, ProductErrors.IMAGE_UPLOAD_FAILED,
                "The image could not be uploaded. Please try again");
        initCause(cause);
    }
}
