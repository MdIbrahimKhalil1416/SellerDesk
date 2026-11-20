package com.sellerdesk.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ImageCleanupTasksTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final ImageCleanupTaskRepository repository = mock(ImageCleanupTaskRepository.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final ImageCleanupTasks tasks = new ImageCleanupTasks(repository, storage, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void orphanCheckIsDueInOneHour() {
        when(repository.save(any())).thenAnswer(inv -> {
            ImageCleanupTask t = inv.getArgument(0);
            t.setId("t1");
            return t;
        });

        assertThat(tasks.scheduleOrphanCheck("shopA", "pub1")).isEqualTo("t1");

        ArgumentCaptor<ImageCleanupTask> saved = ArgumentCaptor.forClass(ImageCleanupTask.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getReason()).isEqualTo(ImageCleanupReason.ORPHAN_UPLOAD);
        assertThat(saved.getValue().getNotBefore()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(saved.getValue().getShopId()).isEqualTo("shopA");
    }

    @Test
    void removalIsDueNow() {
        tasks.scheduleRemoval("shopA", List.of("a", "b"));

        ArgumentCaptor<ImageCleanupTask> saved = ArgumentCaptor.forClass(ImageCleanupTask.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(t -> {
            assertThat(t.getReason()).isEqualTo(ImageCleanupReason.REMOVED);
            assertThat(t.getNotBefore()).isEqualTo(NOW);
        });
    }

    @Test
    void dueTaskIsDeletedAfterTheFileIsDeleted() {
        when(repository.findByNotBeforeLessThanEqualOrderByNotBeforeAsc(any(), any())).thenReturn(List.of(task("t1", "pub1", 0)));

        assertThat(tasks.processDueTasks()).isEqualTo(1);

        verify(storage).delete("pub1");
        verify(repository).deleteById("t1");
    }

    @Test
    void failureIsRetriedWithExponentialBackoff() {
        ImageCleanupTask task = task("t1", "pub1", 2);
        when(repository.findByNotBeforeLessThanEqualOrderByNotBeforeAsc(any(), any())).thenReturn(List.of(task));
        doThrow(new ImageStorageException("down")).when(storage).delete("pub1");

        assertThat(tasks.processDueTasks()).isZero();

        verify(repository, never()).deleteById(anyString());
        verify(repository).save(task);
        assertThat(task.getAttempts()).isEqualTo(3);
        assertThat(task.getNotBefore()).isEqualTo(NOW.plus(Duration.ofMinutes(8)));
    }

    @Test
    void tenthFailureGivesUp() {
        ImageCleanupTask task = task("t1", "pub1", 9);
        when(repository.findByNotBeforeLessThanEqualOrderByNotBeforeAsc(any(), any())).thenReturn(List.of(task));
        doThrow(new ImageStorageException("down")).when(storage).delete("pub1");

        tasks.processDueTasks();

        verify(repository).deleteById("t1");
        verify(repository, never()).save(any());
    }

    @Test
    void oneFailureDoesNotStopTheOthers() {
        when(repository.findByNotBeforeLessThanEqualOrderByNotBeforeAsc(any(), any()))
                .thenReturn(List.of(task("t1", "bad", 0), task("t2", "good", 0)));
        doThrow(new ImageStorageException("down")).when(storage).delete("bad");

        assertThat(tasks.processDueTasks()).isEqualTo(1);
        verify(repository).deleteById("t2");
    }

    @Test
    void listenerDeletesNowAndSwallowsFailures() {
        doThrow(new ImageStorageException("down")).when(storage).delete("bad");
        ImageCleanupListener listener = new ImageCleanupListener(tasks);

        listener.onImagesRemoved(new ImagesRemovedEvent("shopA", List.of("bad", "good")));

        verify(repository).deleteByPublicId("good");
        verify(repository, never()).deleteByPublicId("bad");
    }

    private static ImageCleanupTask task(String id, String publicId, int attempts) {
        ImageCleanupTask t = new ImageCleanupTask();
        t.setId(id);
        t.setPublicId(publicId);
        t.setAttempts(attempts);
        t.setNotBefore(NOW.minusSeconds(1));
        return t;
    }
}
