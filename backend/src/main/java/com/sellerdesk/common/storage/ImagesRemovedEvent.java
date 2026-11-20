package com.sellerdesk.common.storage;

import java.util.List;

/**
 * Published when stored files are no longer referenced (image removed, product deleted).
 * A {@code REMOVED} cleanup task already exists for each id, so the listener only speeds things
 * up; if it fails, the job does the work.
 */
public record ImagesRemovedEvent(String shopId, List<String> publicIds) {

    public ImagesRemovedEvent {
        publicIds = List.copyOf(publicIds);
    }
}
