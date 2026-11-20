package com.sellerdesk.common.storage;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

/**
 * <b>Exception to the tenant rule</b> (docs/SECURITY.md §4): the cleanup job sweeps all shops,
 * so there is no {@code shopId} parameter. Allowed because tasks hold only storage file ids.
 * Only {@code common.storage} uses it.
 */
public interface ImageCleanupTaskRepository extends Repository<ImageCleanupTask, String> {

    ImageCleanupTask save(ImageCleanupTask task);

    void deleteById(String id);

    long deleteByPublicId(String publicId);

    /** Due tasks, oldest first. */
    List<ImageCleanupTask> findByNotBeforeLessThanEqualOrderByNotBeforeAsc(Instant now, Limit limit);
}
