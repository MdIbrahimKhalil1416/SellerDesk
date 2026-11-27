package com.sellerdesk.shop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.ImageTooLargeException;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.storage.ImageCleanupTasks;
import com.sellerdesk.common.storage.ImageStorage;
import com.sellerdesk.common.storage.ImageStorageException;
import com.sellerdesk.common.storage.ImageTypeNotAllowedException;
import com.sellerdesk.common.storage.ImageUploadFailedException;
import com.sellerdesk.common.storage.ImageValidator;
import com.sellerdesk.common.storage.ImagesRemovedEvent;
import com.sellerdesk.common.storage.StoredImage;
import com.sellerdesk.shop.model.Shop;
import com.sellerdesk.shop.model.ShopLogo;
import com.sellerdesk.shop.repository.ShopQueryRepository;
import com.sellerdesk.shop.repository.ShopRepository;
import com.sellerdesk.testsupport.TestImages;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ShopLogoServiceTest {

    private static final String SHOP = "shopA";
    private static final ShopLogo OLD_LOGO =
            new ShopLogo("https://img/old.png", "sellerdesk/shopA/logo/old", 100, 100, 10, "png");

    private final ShopRepository shopRepository = mock(ShopRepository.class);
    private final ShopQueryRepository queryRepository = mock(ShopQueryRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final ImageCleanupTasks cleanupTasks = mock(ImageCleanupTasks.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final ShopLogoService service = new ShopLogoService(shopRepository, queryRepository, currentUser,
            new ImageValidator(), storage, cleanupTasks, events, new TransactionTemplate(new NoOpTransactions()),
            Clock.fixed(Instant.parse("2026-11-28T10:00:00Z"), ZoneOffset.UTC));

    private final MockMultipartFile png = new MockMultipartFile("file", "logo.png", "image/png", TestImages.png());

    @BeforeEach
    void setUp() {
        when(currentUser.shopId()).thenReturn(SHOP);
        when(shopRepository.existsById(SHOP)).thenReturn(true);
        when(shopRepository.findById(SHOP)).thenReturn(Optional.of(shop(null)));
        when(cleanupTasks.scheduleOrphanCheck(eq(SHOP), anyString())).thenReturn("task1");
        when(storage.upload(any(), anyString())).thenAnswer(inv ->
                new StoredImage("https://img/new.png", inv.getArgument(1), 256, 256, 64, "png"));
    }

    @Test
    void firstLogoIsUploadedSavedAndItsOrphanTaskCancelled() {
        when(queryRepository.updateAndGetPrevious(eq(SHOP), any(), any())).thenReturn(Optional.of(shop(null)));

        service.upload(png);

        ArgumentCaptor<String> publicId = ArgumentCaptor.forClass(String.class);
        verify(cleanupTasks).scheduleOrphanCheck(eq(SHOP), publicId.capture());
        assertThat(publicId.getValue()).startsWith("sellerdesk/shopA/logo/");
        verify(storage).upload(TestImages.png(), publicId.getValue());

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(queryRepository).updateAndGetPrevious(eq(SHOP), update.capture(), any());
        ShopLogo saved = (ShopLogo) update.getValue().getUpdateObject().get("$set", Document.class).get("logo");
        assertThat(saved.publicId()).isEqualTo(publicId.getValue());
        assertThat(saved.url()).isEqualTo("https://img/new.png");

        verify(cleanupTasks).cancel("task1");
        verify(cleanupTasks, never()).scheduleRemoval(any(), any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    void replacingTheLogoSchedulesTheOldFileForDeletion() {
        when(queryRepository.updateAndGetPrevious(eq(SHOP), any(), any())).thenReturn(Optional.of(shop(OLD_LOGO)));

        service.upload(png);

        verify(cleanupTasks).scheduleRemoval(SHOP, List.of(OLD_LOGO.publicId()));
        verify(events).publishEvent(new ImagesRemovedEvent(SHOP, List.of(OLD_LOGO.publicId())));
        verify(cleanupTasks).cancel("task1");
    }

    @Test
    void wrongBytesAreRejectedBeforeAnyUpload() {
        MockMultipartFile exe = new MockMultipartFile("file", "logo.png", "image/png", TestImages.exe());

        assertThatThrownBy(() -> service.upload(exe)).isInstanceOf(ImageTypeNotAllowedException.class);
        verifyNoInteractions(storage, cleanupTasks, queryRepository);
    }

    @Test
    void emptyFileIsRejected() {
        MockMultipartFile empty = new MockMultipartFile("file", "logo.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> service.upload(empty)).isInstanceOf(ImageTypeNotAllowedException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void tooLargeIsRejectedBeforeAnyUpload() {
        MockMultipartFile big = new MockMultipartFile("file", "big.jpg", "image/jpeg",
                TestImages.jpegOfSize(6 * 1024 * 1024));

        assertThatThrownBy(() -> service.upload(big)).isInstanceOf(ImageTooLargeException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void uploadFailureIs502AndNothingIsSaved() {
        when(storage.upload(any(), anyString())).thenThrow(new ImageStorageException("down"));

        assertThatThrownBy(() -> service.upload(png))
                .isInstanceOf(ImageUploadFailedException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(502));
        verify(queryRepository, never()).updateAndGetPrevious(any(), any(), any());
        verify(cleanupTasks).cancel("task1");
    }

    @Test
    void databaseFailureAfterUploadDeletesTheNewFileButKeepsItsTask() {
        when(queryRepository.updateAndGetPrevious(eq(SHOP), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.upload(png)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(storage).delete(org.mockito.ArgumentMatchers.startsWith("sellerdesk/shopA/logo/"));
        verify(cleanupTasks, never()).cancel(anyString()); // the job double-checks later
    }

    @Test
    void deleteRemovesTheLogoAndSchedulesItsFile() {
        when(shopRepository.findById(SHOP)).thenReturn(Optional.of(shop(OLD_LOGO)));
        when(queryRepository.updateAndGetPrevious(eq(SHOP), any(), any())).thenReturn(Optional.of(shop(OLD_LOGO)));

        service.delete();

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(queryRepository).updateAndGetPrevious(eq(SHOP), update.capture(), any());
        assertThat(update.getValue().getUpdateObject().get("$unset", Document.class)).containsKey("logo");
        verify(cleanupTasks).scheduleRemoval(SHOP, List.of(OLD_LOGO.publicId()));
        verify(events).publishEvent(new ImagesRemovedEvent(SHOP, List.of(OLD_LOGO.publicId())));
    }

    @Test
    void deleteWithoutLogoChangesNothing() {
        service.delete();

        verifyNoInteractions(queryRepository, cleanupTasks, storage, events);
    }

    private static Shop shop(ShopLogo logo) {
        Shop shop = new Shop();
        shop.setId(SHOP);
        shop.setLogo(logo);
        return shop;
    }

    /** Runs the callback directly: this unit test checks the service, not MongoDB transactions. */
    private static final class NoOpTransactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
