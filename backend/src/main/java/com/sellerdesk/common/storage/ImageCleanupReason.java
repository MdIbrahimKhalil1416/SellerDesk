package com.sellerdesk.common.storage;

public enum ImageCleanupReason {
    /** Written before an upload. Deleted again once the image is saved on the product. */
    ORPHAN_UPLOAD,
    /** The image or its product was removed: the file must go. */
    REMOVED
}
