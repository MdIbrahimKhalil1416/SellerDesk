package com.sellerdesk.testsupport;

import com.sellerdesk.common.storage.ImageStorage;
import com.sellerdesk.common.storage.ImageStorageException;
import com.sellerdesk.common.storage.StoredImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * {@link ImageStorage} for tests: no network, records every call, can be told to fail.
 * Thread-safe, because uploads run in parallel in some tests and deletes run on @Async threads.
 *
 * <p>Registered for all {@link MongoIntegrationTestBase} tests by {@link Config} ({@code @Primary}
 * wins over the real {@code CloudinaryImageStorage}). Reset before each test.
 */
public class FakeImageStorage implements ImageStorage {

    private final List<String> uploads = new CopyOnWriteArrayList<>();
    private final List<String> deletes = new CopyOnWriteArrayList<>();
    private final Set<String> failingDeletes = ConcurrentHashMap.newKeySet();
    private volatile boolean failUploads;
    private volatile boolean failAllDeletes;

    @Override
    public StoredImage upload(byte[] bytes, String publicId) {
        if (failUploads) {
            throw new ImageStorageException("fake upload failure");
        }
        uploads.add(publicId);
        return new StoredImage("https://images.test/" + publicId + ".jpg", publicId, 800, 600, bytes.length, "jpg");
    }

    @Override
    public void delete(String publicId) {
        if (failAllDeletes || failingDeletes.contains(publicId)) {
            throw new ImageStorageException("fake delete failure");
        }
        deletes.add(publicId);
    }

    public List<String> uploads() {
        return List.copyOf(uploads);
    }

    public List<String> deletes() {
        return List.copyOf(deletes);
    }

    public void failUploads(boolean fail) {
        this.failUploads = fail;
    }

    public void failAllDeletes(boolean fail) {
        this.failAllDeletes = fail;
    }

    public void failDeleteOf(String publicId) {
        failingDeletes.add(publicId);
    }

    public void reset() {
        uploads.clear();
        deletes.clear();
        failingDeletes.clear();
        failUploads = false;
        failAllDeletes = false;
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        FakeImageStorage fakeImageStorage() {
            return new FakeImageStorage();
        }
    }
}
