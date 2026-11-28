package com.sellerdesk.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.storage.ImageCleanupTasks;
import com.sellerdesk.common.storage.ImagesRemovedEvent;
import com.sellerdesk.product.dto.AddVariantRequest;
import com.sellerdesk.product.dto.CreateProductRequest;
import com.sellerdesk.product.dto.ProductResponse;
import com.sellerdesk.product.dto.ProductStatusRequest;
import com.sellerdesk.product.dto.UpdateProductRequest;
import com.sellerdesk.product.dto.UpdateVariantRequest;
import com.sellerdesk.product.dto.VariantRequest;
import com.sellerdesk.product.model.Product;
import com.sellerdesk.product.model.ProductImage;
import com.sellerdesk.product.model.ProductStatus;
import com.sellerdesk.product.model.Variant;
import com.sellerdesk.product.model.VariantStock;
import com.sellerdesk.product.repository.ProductQueryRepository;
import com.sellerdesk.product.repository.ProductRepository;
import com.sellerdesk.product.repository.ProductSort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Business rules of {@link ProductService}, with mocked repositories. The shop always comes from
 * {@link CurrentUser} ("shopA"), and every repository call must receive exactly that shop.
 */
class ProductServiceTest {

    private static final String SHOP = "shopA";
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductQueryRepository queryRepository = mock(ProductQueryRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ImageCleanupTasks cleanupTasks = mock(ImageCleanupTasks.class);
    private final ProductUsageChecker usageChecker = mock(ProductUsageChecker.class);

    private final ProductService service = new ProductService(productRepository, queryRepository,
            new ProductMapper(currentUser), currentUser, events, cleanupTasks, List.of(usageChecker),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void loggedInAsShopA() {
        when(currentUser.shopId()).thenReturn(SHOP);
        when(currentUser.userId()).thenReturn("user1");
        when(queryRepository.findSkusInUse(any(), anyCollection(), any())).thenReturn(Set.of());
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> {
            Product p = inv.getArgument(0);
            p.setId("p1");
            p.setVersion(0L);
            return p;
        });
    }

    // ==== Create =========================================================================

    @Nested
    class Create {

        @Test
        void normalizesValuesStampsShopAndStartsWithZeroStock() {
            ProductResponse response = service.create(new CreateProductRequest("  Cotton   T-Shirt ", " Soft ",
                    "  Men's  Wear ", List.of(variant(" ts-red-m ", null, attrs("Color", "Red", "Size", "M"), 45_050L))));

            ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
            verify(productRepository).save(saved.capture());
            Product p = saved.getValue();
            assertThat(p.getShopId()).isEqualTo(SHOP);
            assertThat(p.getCreatedBy()).isEqualTo("user1");
            assertThat(p.getStatus()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(p.getName()).isEqualTo("Cotton T-Shirt");
            assertThat(p.getNameLower()).isEqualTo("cotton t-shirt");
            assertThat(p.getDescription()).isEqualTo("Soft");
            assertThat(p.getCategory()).isEqualTo("Men's Wear");
            assertThat(p.getCategoryKey()).isEqualTo("men's wear");

            Variant v = p.getVariants().getFirst();
            assertThat(v.getSku()).isEqualTo("TS-RED-M");
            assertThat(v.getName()).isEqualTo("Red / M");
            assertThat(v.isActive()).isTrue();
            assertThat(v.getStock()).isEqualTo(VariantStock.empty());
            assertThat(ObjectId.isValid(v.getVariantId())).isTrue();
            assertThat(response.variants().getFirst().sku()).isEqualTo("TS-RED-M");
        }

        @Test
        void givenNameIsKeptAndNoAttributesGiveDefault() {
            service.create(new CreateProductRequest("Mug", null, null, List.of(
                    variant("MUG-1", "Big mug", null, 100L),
                    variant("MUG-2", null, null, 200L))));

            Product p = savedProduct();
            assertThat(p.getVariants()).extracting(Variant::getName).containsExactly("Big mug", "Default");
        }

        @Test
        void minAndMaxPriceComputedFromVariants() {
            service.create(new CreateProductRequest("Mug", null, null, List.of(
                    variant("A", null, attrs("Size", "S"), 500L),
                    variant("B", null, attrs("Size", "L"), 900L))));

            assertThat(savedProduct().getMinPrice()).isEqualTo(500L);
            assertThat(savedProduct().getMaxPrice()).isEqualTo(900L);
        }

        @Test
        void sameSkuTwiceInOneRequestIsAFieldError() {
            assertThatThrownBy(() -> service.create(new CreateProductRequest("Mug", null, null, List.of(
                    variant("mug-1", null, attrs("Size", "S"), 1L),
                    variant(" MUG-1 ", null, attrs("Size", "L"), 1L)))))
                    .isInstanceOfSatisfying(FieldValidationException.class, e ->
                            assertThat(e.getFieldErrors().getFirst().field()).isEqualTo("variants[1].sku"));
            verify(productRepository, never()).save(any());
        }

        @Test
        void sameAttributeCombinationTwiceIs409() {
            assertThatThrownBy(() -> service.create(new CreateProductRequest("Mug", null, null, List.of(
                    variant("A", null, attrs("Color", "Red", "Size", "M"), 1L),
                    variant("B", null, attrs("size", "m", "color", "RED"), 1L)))))
                    .isInstanceOf(AppException.class)
                    .extracting("code").isEqualTo("DUPLICATE_VARIANT_ATTRIBUTES");
        }

        @Test
        void skuOfAnotherProductIs409WithFieldPath() {
            when(queryRepository.findSkusInUse(eq(SHOP), anyCollection(), isNull())).thenReturn(Set.of("B"));

            assertThatThrownBy(() -> service.create(new CreateProductRequest("Mug", null, null, List.of(
                    variant("A", null, attrs("Size", "S"), 1L),
                    variant("b", null, attrs("Size", "L"), 1L)))))
                    .isInstanceOfSatisfying(SkuAlreadyExistsException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("SKU_ALREADY_EXISTS");
                        assertThat(e.getFieldErrors()).extracting(f -> f.field()).containsExactly("variants[1].sku");
                    });
            verify(productRepository, never()).save(any());
        }

        /** Two requests race: our check said "free", but the unique index rejects the insert. */
        @Test
        void duplicateKeyOnInsertBecomesSkuAlreadyExists() {
            when(productRepository.save(any(Product.class))).thenThrow(new DuplicateKeyException("E11000"));
            when(queryRepository.findSkusInUse(eq(SHOP), anyCollection(), isNull()))
                    .thenReturn(Set.of(), Set.of("A"));

            assertThatThrownBy(() -> service.create(new CreateProductRequest("Mug", null, null,
                    List.of(variant("A", null, null, 1L)))))
                    .isInstanceOfSatisfying(SkuAlreadyExistsException.class, e ->
                            assertThat(e.getFieldErrors()).extracting(f -> f.field()).containsExactly("variants[0].sku"));
        }

        @Test
        void publishesProductChanged() {
            service.create(new CreateProductRequest("Mug", null, null, List.of(variant("A", null, null, 1L))));
            verify(events).publishEvent(new ProductChangedEvent(SHOP, "p1"));
        }
    }

    // ==== Update / status ================================================================

    @Nested
    class UpdateAndStatus {

        @Test
        void updateSetsOnlyProductFieldsWithVersionCondition() {
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(product()));

            service.update("p1", new UpdateProductRequest(" New  name ", null, " Shirts ", 4L));

            ArgumentCaptor<Criteria> condition = ArgumentCaptor.forClass(Criteria.class);
            ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
            verify(queryRepository).modify(eq(SHOP), eq("p1"), condition.capture(), update.capture());
            assertThat(condition.getValue().getCriteriaObject()).containsEntry("version", 4L);
            Document set = setOf(update.getValue());
            assertThat(set).containsEntry("name", "New name").containsEntry("nameLower", "new name")
                    .containsEntry("category", "Shirts").containsEntry("categoryKey", "shirts")
                    .containsEntry("updatedAt", NOW);
            assertThat(update.getValue().getUpdateObject().get("$unset", Document.class)).containsKey("description");
            assertThat(update.getValue().getUpdateObject().get("$inc", Document.class)).containsEntry("version", 1);
            assertNoStockWritten(update.getValue());
            verify(events).publishEvent(new ProductChangedEvent(SHOP, "p1"));
        }

        @Test
        void staleVersionIs409() {
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.empty());
            when(productRepository.existsByIdAndShopId("p1", SHOP)).thenReturn(true);

            assertThatThrownBy(() -> service.update("p1", new UpdateProductRequest("X", null, null, 1L)))
                    .isInstanceOf(OptimisticLockingFailureException.class);
            verify(events, never()).publishEvent(any());
        }

        @Test
        void unknownOrOtherShopsProductIs404() {
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.empty());
            when(productRepository.existsByIdAndShopId("p1", SHOP)).thenReturn(false);

            assertThatThrownBy(() -> service.update("p1", new UpdateProductRequest("X", null, null, 1L)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .extracting("code").isEqualTo("NOT_FOUND");
        }

        @Test
        void archiveAndUnarchiveSetStatus() {
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(product()));

            service.changeStatus("p1", new ProductStatusRequest(ProductStatus.ARCHIVED, 0L));
            service.changeStatus("p1", new ProductStatusRequest(ProductStatus.ACTIVE, 1L));

            ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
            verify(queryRepository, org.mockito.Mockito.times(2)).modify(eq(SHOP), eq("p1"), any(), update.capture());
            assertThat(setOf(update.getAllValues().get(0))).containsEntry("status", ProductStatus.ARCHIVED);
            assertThat(setOf(update.getAllValues().get(1))).containsEntry("status", ProductStatus.ACTIVE);
        }
    }

    // ==== Variants =======================================================================

    @Nested
    class AddVariant {

        @Test
        void pushesNewVariantAndRecomputesPrices() {
            givenProduct(product());
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(product()));

            service.addVariant("p1", addVariant(" ts-blue-m ", attrs("Color", "Blue", "Size", "M"), 99_000L, 2L));

            ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
            ArgumentCaptor<Criteria> condition = ArgumentCaptor.forClass(Criteria.class);
            verify(queryRepository).modify(eq(SHOP), eq("p1"), condition.capture(), update.capture());
            Variant pushed = (Variant) update.getValue().getUpdateObject().get("$push", Document.class).get("variants");
            assertThat(pushed.getSku()).isEqualTo("TS-BLUE-M");
            assertThat(pushed.getName()).isEqualTo("Blue / M");
            assertThat(pushed.getStock()).isEqualTo(VariantStock.empty());
            assertThat(setOf(update.getValue())).containsEntry("minPrice", 45_050L).containsEntry("maxPrice", 99_000L);
            // Atomic guards: version, SKU not yet in the product, fewer than 100 variants.
            assertThat(condition.getValue().getCriteriaObject().toJson())
                    .contains("\"version\": 2").contains("\"variants.sku\": {\"$ne\": \"TS-BLUE-M\"}")
                    .contains("\"variants.99\": {\"$exists\": false}");
            verify(queryRepository).findSkusInUse(SHOP, Set.of("TS-BLUE-M"), "p1");
            verify(events).publishEvent(new ProductChangedEvent(SHOP, "p1"));
        }

        @Test
        void limitOf100VariantsIs409() {
            Product p = product();
            List<Variant> many = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                many.add(variantModel("SKU" + i, attrs("N", "" + i), 1, true));
            }
            p.setVariants(many);
            givenProduct(p);

            assertThatThrownBy(() -> service.addVariant("p1", addVariant("NEW", null, 1L, 2L)))
                    .isInstanceOf(AppException.class).extracting("code").isEqualTo("VARIANT_LIMIT_REACHED");
            verify(queryRepository, never()).modify(any(), any(), any(), any());
        }

        @Test
        void skuAlreadyInThisProductIs409() {
            givenProduct(product());
            assertThatThrownBy(() -> service.addVariant("p1", addVariant("ts-red-m", attrs("Size", "XL"), 1L, 2L)))
                    .isInstanceOf(SkuAlreadyExistsException.class);
        }

        @Test
        void skuOfAnotherProductIs409() {
            givenProduct(product());
            when(queryRepository.findSkusInUse(SHOP, Set.of("OTHER-1"), "p1")).thenReturn(Set.of("OTHER-1"));
            assertThatThrownBy(() -> service.addVariant("p1", addVariant("other-1", attrs("Size", "XL"), 1L, 2L)))
                    .isInstanceOfSatisfying(SkuAlreadyExistsException.class, e ->
                            assertThat(e.getFieldErrors().getFirst().field()).isEqualTo("sku"));
        }

        @Test
        void sameAttributesAsActiveVariantIs409() {
            givenProduct(product());
            assertThatThrownBy(() -> service.addVariant("p1", addVariant("NEW", attrs("color", "red", "size", "m"), 1L, 2L)))
                    .isInstanceOf(AppException.class).extracting("code").isEqualTo("DUPLICATE_VARIANT_ATTRIBUTES");
        }

        /** Fixing a SKU typo: deactivate the old variant, add a new one with the same attributes. */
        @Test
        void sameAttributesAsInactiveVariantIsAllowed() {
            Product p = product();
            p.getVariants().getFirst().setActive(false);
            givenProduct(p);
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(p));

            service.addVariant("p1", addVariant("TS-RED-M2", attrs("Color", "Red", "Size", "M"), 1L, 2L));
        }

        @Test
        void staleVersionIs409BeforeAnyWrite() {
            givenProduct(product());
            assertThatThrownBy(() -> service.addVariant("p1", addVariant("NEW", null, 1L, 1L)))
                    .isInstanceOf(OptimisticLockingFailureException.class);
            verify(queryRepository, never()).modify(any(), any(), any(), any());
        }

        /** The atomic $push matched nothing because a parallel request added the same SKU. */
        @Test
        void raceOnSameSkuIsReportedAsSkuConflict() {
            Product after = product();
            after.getVariants().add(variantModel("NEW", attrs("Size", "XL"), 1, true));
            when(productRepository.findByIdAndShopId("p1", SHOP)).thenReturn(Optional.of(product()), Optional.of(after));
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addVariant("p1", addVariant("NEW", attrs("Size", "XXL"), 1L, 2L)))
                    .isInstanceOf(SkuAlreadyExistsException.class);
        }
    }

    @Nested
    class UpdateVariant {

        @Test
        void deactivatingRecomputesPricesAndNeverWritesStockOrSku() {
            Product p = product();
            p.getVariants().add(variantModel("TS-BIG", attrs("Size", "XXL"), 80_000, true));
            givenProduct(p);
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(p));
            String variantId = p.getVariants().get(1).getVariantId();

            service.updateVariant("p1", variantId, new UpdateVariantRequest(null, null, null, null, null, false, null, 2L));

            ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
            ArgumentCaptor<Criteria> condition = ArgumentCaptor.forClass(Criteria.class);
            verify(queryRepository).modify(eq(SHOP), eq("p1"), condition.capture(), update.capture());
            Document set = setOf(update.getValue());
            assertThat(set).containsEntry("variants.$.active", false)
                    .containsEntry("minPrice", 45_050L).containsEntry("maxPrice", 45_050L);
            assertThat(set.keySet()).noneMatch(k -> k.contains("sku"));
            assertNoStockWritten(update.getValue());
            assertThat(condition.getValue().getCriteriaObject().toJson()).contains(variantId);
            verify(events).publishEvent(new ProductChangedEvent(SHOP, "p1"));
        }

        @Test
        void allVariantsInactiveGiveNullPrices() {
            Product p = product();
            givenProduct(p);
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(p));

            service.updateVariant("p1", p.getVariants().getFirst().getVariantId(),
                    new UpdateVariantRequest(null, null, null, null, null, false, null, 2L));

            ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
            verify(queryRepository).modify(eq(SHOP), eq("p1"), any(), update.capture());
            assertThat(setOf(update.getValue())).containsEntry("minPrice", null).containsEntry("maxPrice", null);
        }

        @Test
        void defaultNameFollowsNewAttributes() {
            Product p = product();
            givenProduct(p);
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(p));

            service.updateVariant("p1", p.getVariants().getFirst().getVariantId(),
                    new UpdateVariantRequest(null, attrs("Color", "Red", "Size", "L"), null, null, null, null, null, 2L));

            assertThat(setOfLastModify()).containsEntry("variants.$.name", "Red / L");
        }

        @Test
        void customNameIsKeptWhenAttributesChange() {
            Product p = product();
            p.getVariants().getFirst().setName("Special");
            givenProduct(p);
            when(queryRepository.modify(eq(SHOP), eq("p1"), any(), any())).thenReturn(Optional.of(p));

            service.updateVariant("p1", p.getVariants().getFirst().getVariantId(),
                    new UpdateVariantRequest(null, attrs("Color", "Red", "Size", "L"), 50_000L, null, null, null, null, 2L));

            assertThat(setOfLastModify()).containsEntry("variants.$.name", "Special")
                    .containsEntry("variants.$.price", 50_000L);
        }

        @Test
        void reactivatingIntoDuplicateAttributesIs409() {
            Product p = product();
            Variant old = variantModel("TS-RED-M-OLD", attrs("Color", "Red", "Size", "M"), 1, false);
            p.getVariants().add(old);
            givenProduct(p);

            assertThatThrownBy(() -> service.updateVariant("p1", old.getVariantId(),
                    new UpdateVariantRequest(null, null, null, null, null, true, null, 2L)))
                    .isInstanceOf(AppException.class).extracting("code").isEqualTo("DUPLICATE_VARIANT_ATTRIBUTES");
        }

        @Test
        void unknownVariantIs404() {
            givenProduct(product());
            assertThatThrownBy(() -> service.updateVariant("p1", new ObjectId().toHexString(),
                    new UpdateVariantRequest("x", null, null, null, null, null, null, 2L)))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.updateVariant("p1", "not-an-id",
                    new UpdateVariantRequest("x", null, null, null, null, null, null, 2L)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void unknownProductIs404() {
            when(productRepository.findByIdAndShopId("p1", SHOP)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.updateVariant("p1", new ObjectId().toHexString(),
                    new UpdateVariantRequest("x", null, null, null, null, null, null, 2L)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ==== Delete =========================================================================

    @Nested
    class Delete {

        @Test
        void refusedWhenAModuleReportsUsage() {
            givenProduct(product());
            when(usageChecker.isUsed(SHOP, "p1")).thenReturn(true);

            assertThatThrownBy(() -> service.delete("p1")).isInstanceOf(ProductInUseException.class)
                    .extracting("code").isEqualTo("PRODUCT_IN_USE");
            verify(queryRepository, never()).deleteIfUnstocked(any(), any());
        }

        @Test
        void refusedWhenAVariantHasStock() {
            Product p = product();
            p.getVariants().getFirst().setStock(new VariantStock(0, 1, -1));
            givenProduct(p);

            assertThatThrownBy(() -> service.delete("p1")).isInstanceOf(ProductInUseException.class);
        }

        @Test
        void stockArrivingBetweenCheckAndDeleteIsStillRefused() {
            givenProduct(product());
            when(queryRepository.deleteIfUnstocked(SHOP, "p1")).thenReturn(0L);
            when(productRepository.existsByIdAndShopId("p1", SHOP)).thenReturn(true);

            assertThatThrownBy(() -> service.delete("p1")).isInstanceOf(ProductInUseException.class);
        }

        @Test
        void deletesAndSchedulesImageCleanup() {
            givenProduct(product());
            when(queryRepository.deleteIfUnstocked(SHOP, "p1")).thenReturn(1L);

            service.delete("p1");

            verify(cleanupTasks).scheduleRemoval(SHOP, List.of("sellerdesk/shopA/products/i1"));
            verify(events).publishEvent(new ImagesRemovedEvent(SHOP, List.of("sellerdesk/shopA/products/i1")));
            verify(events).publishEvent(new ProductChangedEvent(SHOP, "p1"));
        }
    }

    // ==== Read ===========================================================================

    @Nested
    class Read {

        @Test
        void listBuildsSearchFromParameters() {
            when(queryRepository.search(eq(SHOP), any())).thenReturn(new ProductQueryRepository.SearchResult(List.of(product()), 41));

            var page = service.list("  ts.red ", " SHIRTS ", "ALL", "name,asc", 2, 20);

            ArgumentCaptor<ProductQueryRepository.Search> search = ArgumentCaptor.forClass(ProductQueryRepository.Search.class);
            verify(queryRepository).search(eq(SHOP), search.capture());
            assertThat(search.getValue().skuExact()).isEqualTo("TS.RED");
            assertThat(search.getValue().namePrefixRegex()).isEqualTo("^ts\\.red");
            assertThat(search.getValue().categoryKey()).isEqualTo("shirts");
            assertThat(search.getValue().status()).isNull();
            assertThat(search.getValue().sort()).isEqualTo(ProductSort.NAME_ASC);
            assertThat(page.totalElements()).isEqualTo(41);
            assertThat(page.totalPages()).isEqualTo(3);
            assertThat(page.page()).isEqualTo(2);
        }

        @Test
        void listDefaultsToActiveAndRecentlyUpdated() {
            when(queryRepository.search(eq(SHOP), any())).thenReturn(new ProductQueryRepository.SearchResult(List.of(), 0));

            service.list(null, null, null, null, 0, 20);

            ArgumentCaptor<ProductQueryRepository.Search> search = ArgumentCaptor.forClass(ProductQueryRepository.Search.class);
            verify(queryRepository).search(eq(SHOP), search.capture());
            assertThat(search.getValue().status()).isEqualTo(ProductStatus.ACTIVE);
            assertThat(search.getValue().sort()).isEqualTo(ProductSort.UPDATED_DESC);
            assertThat(search.getValue().skuExact()).isNull();
        }

        @Test
        void unknownSortOrStatusIs400() {
            assertThatThrownBy(() -> service.list(null, null, null, "price,asc", 0, 20))
                    .isInstanceOf(FieldValidationException.class);
            assertThatThrownBy(() -> service.list(null, null, "DELETED", null, 0, 20))
                    .isInstanceOf(FieldValidationException.class);
        }

        @Test
        void lookupSkipsProductsWithoutActiveVariants() {
            Product inactive = product();
            inactive.setId("p2");
            inactive.getVariants().forEach(v -> v.setActive(false));
            when(queryRepository.findActiveForLookup(SHOP)).thenReturn(List.of(product(), inactive));

            assertThat(service.lookup()).extracting(i -> i.productId()).containsExactly("p1");
        }

        @Test
        void getUsesTheShopFromTheToken() {
            givenProduct(product());
            assertThat(service.get("p1").id()).isEqualTo("p1");
            verify(productRepository).findByIdAndShopId("p1", SHOP);
        }
    }

    // ---- helpers ----------------------------------------------------------------------

    private void givenProduct(Product product) {
        when(productRepository.findByIdAndShopId("p1", SHOP)).thenReturn(Optional.of(product));
    }

    private Product savedProduct() {
        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(saved.capture());
        return saved.getValue();
    }

    private Document setOfLastModify() {
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(queryRepository).modify(eq(SHOP), eq("p1"), any(), update.capture());
        return setOf(update.getValue());
    }

    private static Document setOf(Update update) {
        return update.getUpdateObject().get("$set", Document.class);
    }

    /** The lost-update rule: product writes never contain a stock field. */
    private static void assertNoStockWritten(Update update) {
        assertThat(update.getUpdateObject().toString()).doesNotContain("stock");
    }

    static Map<String, String> attrs(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static VariantRequest variant(String sku, String name, Map<String, String> attributes, Long price) {
        return new VariantRequest(sku, name, attributes, price, null, null);
    }

    private static AddVariantRequest addVariant(String sku, Map<String, String> attributes, Long price, Long version) {
        return new AddVariantRequest(sku, null, attributes, price, null, null, version);
    }

    static Variant variantModel(String sku, Map<String, String> attributes, long price, boolean active) {
        Variant v = new Variant();
        v.setVariantId(new ObjectId().toHexString());
        v.setSku(sku);
        v.setAttributes(attributes);
        v.setName(ProductRules.defaultVariantName(attributes));
        v.setPrice(price);
        v.setActive(active);
        v.setStock(VariantStock.empty());
        return v;
    }

    /** Product p1 of shop A, version 2, one active variant "Red / M" at ৳450.50 and one image. */
    static Product product() {
        Product p = new Product();
        p.setId("p1");
        p.setShopId(SHOP);
        p.setName("T-Shirt");
        p.setStatus(ProductStatus.ACTIVE);
        p.setVersion(2L);
        ProductImage image = new ProductImage();
        image.setImageId(new ObjectId().toHexString());
        image.setUrl("https://img/i1.jpg");
        image.setPublicId("sellerdesk/shopA/products/i1");
        p.setImages(new ArrayList<>(List.of(image)));
        p.setVariants(new ArrayList<>(List.of(variantModel("TS-RED-M", attrs("Color", "Red", "Size", "M"), 45_050, true))));
        p.setMinPrice(45_050L);
        p.setMaxPrice(45_050L);
        return p;
    }
}
