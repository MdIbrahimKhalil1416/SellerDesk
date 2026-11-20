package com.sellerdesk.common.storage;

/** The image provider failed (network error, bad credentials, rejected file...). */
public class ImageStorageException extends RuntimeException {

    public ImageStorageException(String message) {
        super(message);
    }

    public ImageStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
