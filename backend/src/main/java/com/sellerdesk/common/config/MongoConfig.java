package com.sellerdesk.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MongoDB setup.
 *
 * <ul>
 *   <li>Auditing: fields annotated with {@code @CreatedDate} / {@code @LastModifiedDate}
 *       (see {@link com.sellerdesk.common.model.AuditableDocument}) are filled in automatically on save.</li>
 *   <li>Transactions: Spring Boot does <b>not</b> create a MongoDB transaction manager by itself,
 *       because transactions need a replica set (Atlas and Testcontainers have one; a plain local
 *       {@code mongod} does not). Without this bean, {@code @Transactional} would silently do nothing.
 *       See docs/SCHEMA.md §11.</li>
 * </ul>
 */
@Configuration
@EnableMongoAuditing
public class MongoConfig {

    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
        return new MongoTransactionManager(databaseFactory);
    }

    /** For running a block of code in a transaction without {@code @Transactional}. */
    @Bean
    TransactionTemplate transactionTemplate(MongoTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
