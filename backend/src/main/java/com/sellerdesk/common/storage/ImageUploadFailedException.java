package com.sellerdesk.common.storage;

import com.sellerdesk.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * The image storage (Cloudinary) did not accept the upload → {@code 502 IMAGE_UPLOAD_FAILED}.
 * 502 "Bad Gateway" = our server is fine, but a service behind it failed; the client may retry.
 * Shared by every image upload (product images, shop logo).
 */
public class ImageUploadFailedException extends AppException {

    public static final String CODE = "IMAGE_UPLOAD_FAILED";

    public ImageUploadFailedException(Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, CODE, "The image could not be uploaded. Please try again");
        initCause(cause);
    }
}
