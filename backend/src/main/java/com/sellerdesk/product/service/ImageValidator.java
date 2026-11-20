package com.sellerdesk.product.service;

import com.sellerdesk.common.error.ImageTooLargeException;
import org.springframework.stereotype.Component;

/**
 * Checks an uploaded image before it is sent to the storage (docs/modules/PRODUCT.md §4).
 *
 * <p>The type is decided by the file's first bytes ("magic bytes"), <b>not</b> by its
 * {@code Content-Type} header or file name: the client controls both, so a renamed {@code .exe}
 * with {@code Content-Type: image/png} must still be rejected.
 */
@Component
public class ImageValidator {

    /** 5 MB, the same as {@code spring.servlet.multipart.max-file-size}. */
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    public enum ImageType { JPEG, PNG, WEBP }

    /**
     * @throws ImageTooLargeException        over 5 MB → 413
     * @throws ImageTypeNotAllowedException empty, or not JPEG / PNG / WebP → 400
     */
    public ImageType validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ImageTypeNotAllowedException();
        }
        if (bytes.length > MAX_BYTES) {
            throw new ImageTooLargeException();
        }
        if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return ImageType.JPEG;
        }
        if (startsWith(bytes, 0, 0x89, 0x50, 0x4E, 0x47)) { // \x89 P N G
            return ImageType.PNG;
        }
        // "RIFF" <4 bytes length> "WEBP"
        if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
            return ImageType.WEBP;
        }
        throw new ImageTypeNotAllowedException();
    }

    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
