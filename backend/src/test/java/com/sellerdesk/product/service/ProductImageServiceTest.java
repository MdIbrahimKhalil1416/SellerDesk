package com.sellerdesk.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.error.ImageTooLargeException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.storage.ImageCleanupTasks;
import com.sellerdesk.common.storage.ImageStorage;
import com.sellerdesk.common.storage.ImageStorageException;
import com.sellerdesk.common.storage.ImageTypeNotAllowedException;
import com.sellerdesk.common.storage.ImageUploadFailedException;
import com.sellerdesk.common.storage.ImageValidator;
import com.sellerdesk.common.storage.ImagesRemovedEvent;
import com.sellerdesk.common.storage.StoredImage;
import com.sellerdesk.product.dto.ReorderImagesRequest;
import com.sellerdesk.product.model.Product;
import com.sellerdesk.product.model.ProductImage;
import com.sellerdesk.product.repository.ProductQueryRepository;
import com.sellerdesk.product.repository.ProductRepository;
import com.sellerdesk.testsupport.TestImages;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.mock.web.MockMultipartFile;

/** The upload flow of docs/modules/PRODUCT.md §4, step by step, with every failure point. */
class ProductImageServiceTest {

    private static final String SHOP = "shopA";

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductQueryRepository queryRepository = mock(ProductQueryRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final ImageCleanupTasks cleanupTasks = mock(ImageCleanupTasks.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final ProductImageService service = new ProductImageService(productRepository, queryRepository,
            new ProductMapper(currentUser), currentUser, new ImageValidator(), storage, cleanupTasks, events,
            Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC));

    private final MockMultipartFile png = new MockMultipartFile("file", "photo.png", "image/png", TestImages.png());

    @BeforeEach
    void setUp() {
        when(currentUser.shopId()).thenReturn(SHOP);
        when(cleanupTasks.scheduleOrphanCheck(eq(SHOP), anyString())).thenReturn("task1");
        when(storage.upload(any(), anyString())).thenAnswer(inv ->
                new StoredImage("https://img/x.png", inv.getArgument(1), 800, 600, 64, "png"));
    }

    @Test
    void successUploadsSavesAndCancelsTheOrphanTask() {
        givenProduct(productWithImages(0));
        when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(productWithImages(1)));

        service.upload("p1", png);

        ArgumentCaptor<String> publicId = ArgumentCaptor.forClass(String.class);
        verify(cleanupTasks).scheduleOrphanCheck(eq(SHOP), publicId.capture());
        assertThat(publicId.getValue()).startsWith("sellerdesk/shopA/products/");
        verify(storage).upload(TestImages.png(), publicId.getValue());

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(queryRepository).modify(eq(SHOP), eq("p1"), any(), update.capture());
        ProductImage pushed = (ProductImage) update.getValue().getUpdateObject().get("$push", Document.class).get("images");
        assertThat(pushed.getPublicId()).isEqualTo(publicId.getValue());
        assertThat(pushed.getUrl()).isEqualTo("https://img/x.png");

        verify(cleanupTasks).cancel("task1");
        verify(storage, never()).delete(anyString());
        verify(events).publishEvent(new ProductChangedEvent(SHOP, "p1"));
    }

    @Test
    void eightImagesAlreadyMeansNoUploadAtAll() {
        givenProduct(productWithImages(8));

        assertThatThrownBy(() -> service.upload("p1", png))
                .isInstanceOf(AppException.class).extracting("code").isEqualTo("IMAGE_LIMIT_REACHED");
        verifyNoInteractions(storage, cleanupTasks);
    }

    @Test
    void wrongBytesAreRejectedBeforeUpload() {
        givenProduct(productWithImages(0));
        MockMultipartFile exe = new MockMultipartFile("file", "photo.png", "image/png", TestImages.exe());

        assertThatThrownBy(() -> service.upload("p1", exe)).isInstanceOf(ImageTypeNotAllowedException.class);
        verifyNoInteractions(storage, cleanupTasks);
    }

    @Test
    void tooLargeIsRejectedBeforeUpload() {
        givenProduct(productWithImages(0));
        MockMultipartFile big = new MockMultipartFile("file", "big.jpg", "image/jpeg",
                TestImages.jpegOfSize(6 * 1024 * 1024));

        assertThatThrownBy(() -> service.upload("p1", big)).isInstanceOf(ImageTooLargeException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void uploadFailureMeansNoDatabaseWriteAndTaskRemoved() {
        givenProduct(productWithImages(0));
        when(storage.upload(any(), anyString())).thenThrow(new ImageStorageException("down"));

        assertThatThrownBy(() -> service.upload("p1", png))
                .isInstanceOf(ImageUploadFailedException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(502));
        verify(queryRepository, never()).modify(any(), any(), any(), any());
        verify(cleanupTasks).cancel("task1");
    }

    @Test
    void databaseFailureDeletesTheFileImmediatelyAndKeepsTheTask() {
        givenProduct(productWithImages(0));
        when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.upload("p1", png)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(storage).delete(anyString());
        verify(cleanupTasks, never()).cancel(anyString());
    }

    /** A parallel upload took the 8th slot between our check and the $push. */
    @Test
    void notSavedBecauseLimitWasReachedDeletesFileAndCancelsTask() {
        givenProduct(productWithImages(7));
        when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.empty());
        when(productRepository.existsByIdAndShopId("p1", SHOP)).thenReturn(true);

        assertThatThrownBy(() -> service.upload("p1", png))
                .isInstanceOf(AppException.class).extracting("code").isEqualTo("IMAGE_LIMIT_REACHED");
        verify(storage).delete(anyString());
        verify(cleanupTasks).cancel("task1");
    }

    @Test
    void notSavedAndImmediateDeleteFailsKeepsTheTaskForTheJob() {
        givenProduct(productWithImages(0));
        when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.empty());
        when(productRepository.existsByIdAndShopId("p1", SHOP)).thenReturn(false);
        doThrow(new ImageStorageException("down")).when(storage).delete(anyString());

        assertThatThrownBy(() -> service.upload("p1", png)).isInstanceOf(ResourceNotFoundException.class);
        verify(cleanupTasks, never()).cancel(anyString());
    }

    @Test
    void uploadToUnknownProductIs404WithoutUpload() {
        when(productRepository.findByIdAndShopId("p1", SHOP)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upload("p1", png)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(storage, cleanupTasks);
    }

    @Test
    void deleteImagePullsItAndSchedulesFileRemoval() {
        Product product = productWithImages(2);
        givenProduct(product);
        ProductImage image = product.getImages().get(1);
        when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(productWithImages(1)));

        service.delete("p1", image.getImageId());

        verify(cleanupTasks).scheduleRemoval(SHOP, List.of(image.getPublicId()));
        verify(events).publishEvent(new ImagesRemovedEvent(SHOP, List.of(image.getPublicId())));
        verify(events).publishEvent(new ProductChangedEvent(SHOP, "p1"));
        verify(storage, never()).delete(anyString()); // done after commit by the listener
    }

    @Test
    void deleteUnknownImageIs404() {
        givenProduct(productWithImages(1));
        assertThatThrownBy(() -> service.delete("p1", new ObjectId().toHexString()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete("p1", "garbage")).isInstanceOf(ResourceNotFoundException.class);
        verify(queryRepository, never()).modify(any(), any(), any(), any());
    }

    @Test
    void reorderNeedsExactlyTheSameSet() {
        Product product = productWithImages(2);
        givenProduct(product);
        String a = product.getImages().get(0).getImageId();
        String b = product.getImages().get(1).getImageId();

        assertThatThrownBy(() -> service.reorder("p1", new ReorderImagesRequest(List.of(a), 2L)))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.reorder("p1", new ReorderImagesRequest(List.of(a, a), 2L)))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.reorder("p1", new ReorderImagesRequest(List.of(a, new ObjectId().toHexString()), 2L)))
                .isInstanceOf(FieldValidationException.class);

        when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(product));
        service.reorder("p1", new ReorderImagesRequest(List.of(b, a), 2L));

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(queryRepository).modify(eq(SHOP), eq("p1"), any(), update.capture());
        @SuppressWarnings("unchecked")
        List<ProductImage> ordered = (List<ProductImage>) update.getValue().getUpdateObject().get("$set", Document.class).get("images");
        assertThat(ordered).extracting(ProductImage::getImageId).containsExactly(b, a);
    }

    @Test
    void reorderWithStaleVersionIs409() {
        givenProduct(productWithImages(1));
        assertThatThrownBy(() -> service.reorder("p1", new ReorderImagesRequest(List.of("x"), 1L)))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private void givenProduct(Product product) {
        when(productRepository.findByIdAndShopId("p1", SHOP)).thenReturn(Optional.of(product));
    }

    private static Product productWithImages(int count) {
        Product p = ProductServiceTest.product();
        List<ProductImage> images = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ProductImage image = new ProductImage();
            image.setImageId(new ObjectId().toHexString());
            image.setUrl("https://img/" + i + ".jpg");
            image.setPublicId("sellerdesk/shopA/products/" + image.getImageId());
            images.add(image);
        }
        p.setImages(images);
        return p;
    }
}
