package com.sellerdesk.common.error;

import org.springframework.http.HttpStatus;

/**
 * An uploaded image is bigger than allowed → {@code 413 IMAGE_TOO_LARGE}.
 *
 * <p>Lives in {@code common} because two places produce it: the image validator (size check on
 * the bytes) and {@link GlobalExceptionHandler} (Spring's multipart limit was hit first).
 */
public class ImageTooLargeException extends AppException {

    public static final String CODE = "IMAGE_TOO_LARGE";
    public static final String MESSAGE = "The image is too large. The maximum size is 5 MB";

    public ImageTooLargeException() {
        super(HttpStatus.CONTENT_TOO_LARGE, CODE, MESSAGE);
    }
}
