package com.sellerdesk.common.model;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * Base class for MongoDB documents that track when they were created and last changed.
 *
 * <p>Spring Data fills these fields on save because auditing is enabled in
 * {@link com.sellerdesk.common.config.MongoConfig}. Never set them by hand.
 * Times are UTC {@link Instant}s.
 */
@Getter
@Setter
public abstract class AuditableDocument {

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
