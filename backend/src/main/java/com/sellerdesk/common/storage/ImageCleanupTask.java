package com.sellerdesk.common.storage;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * "This file in the image storage may have to be deleted" (docs/modules/PRODUCT.md §2).
 *
 * <p>Holds only file ids, never shop business data. That is why its repository may sweep all
 * shops without a {@code shopId} filter (docs/SECURITY.md §4, the one exception).
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
@Document("image_cleanup_tasks")
public class ImageCleanupTask {

    @Id
    private String id;

    /** For logging and tracing only. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String shopId;

    private String publicId;

    private ImageCleanupReason reason;

    /** Failed delete attempts so far. */
    private int attempts;

    /** The cleanup job ignores the task until then. */
    @Indexed
    private Instant notBefore;

    private Instant createdAt;
}
