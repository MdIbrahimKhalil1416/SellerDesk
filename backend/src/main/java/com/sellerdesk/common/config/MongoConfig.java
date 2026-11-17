package com.sellerdesk.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

/**
 * Turns on MongoDB auditing: fields annotated with {@code @CreatedDate} / {@code @LastModifiedDate}
 * (see {@link com.sellerdesk.common.model.AuditableDocument}) are filled in automatically on save.
 */
@Configuration
@EnableMongoAuditing
public class MongoConfig {
}
