package com.sellerdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerdesk.common.model.AuditableDocument;
import com.sellerdesk.testsupport.MongoIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Basic checks against a real MongoDB: right database, auditing, and that the indexes from the
 * model annotations exist (and survive the per-test cleanup, which deletes documents only).
 */
class MongoIntegrationTest extends MongoIntegrationTestBase {

    @Test
    void connectedToATestDatabase() {
        assertThat(mongoTemplate.getDb().getName()).endsWith("-test");
    }

    @Test
    void auditingFillsCreatedAtAndUpdatedAt() {
        AuditProbe saved = mongoTemplate.save(new AuditProbe());

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void uniqueAndTtlIndexesExist() {
        assertThat(mongoTemplate.indexOps("users").getIndexInfo())
                .anySatisfy(index -> {
                    assertThat(index.isUnique()).isTrue();
                    assertThat(index.getIndexFields()).extracting(f -> f.getKey()).containsExactly("email");
                });
        assertThat(mongoTemplate.indexOps("otp_tokens").getIndexInfo())
                .extracting(IndexInfo::getExpireAfter)
                .anySatisfy(ttl -> assertThat(ttl).hasValueSatisfying(d -> assertThat(d).isZero()));
    }

    @Document("audit_probe")
    static class AuditProbe extends AuditableDocument {
        @Id
        String id;
    }
}
