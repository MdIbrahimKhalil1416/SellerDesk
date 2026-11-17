package com.sellerdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.sellerdesk.common.model.AuditableDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Full application context against a real MongoDB (Testcontainers).
 * Skipped automatically when Docker is not available on the machine.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class MongoIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Test
    void contextLoads() {
        assertThat(mongoTemplate.getDb().getName()).isNotBlank();
    }

    @Test
    void auditingFillsCreatedAtAndUpdatedAt() {
        AuditProbe saved = mongoTemplate.save(new AuditProbe());

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Document("audit_probe")
    static class AuditProbe extends AuditableDocument {
        @Id
        String id;
    }
}
