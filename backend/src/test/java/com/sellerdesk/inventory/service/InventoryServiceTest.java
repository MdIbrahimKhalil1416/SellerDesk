package com.sellerdesk.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.service.UserService;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.AppException;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.error.ResourceNotFoundException;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.transaction.TransactionRunner;
import com.sellerdesk.inventory.dto.AdjustmentRequest;
import com.sellerdesk.inventory.dto.RestockRequest;
import com.sellerdesk.inventory.dto.StockChangeResponse;
import com.sellerdesk.inventory.dto.StockRow;
import com.sellerdesk.inventory.model.AdjustmentReason;
import com.sellerdesk.inventory.model.MovementType;
import com.sellerdesk.inventory.model.StockMovement;
import com.sellerdesk.inventory.repository.StockMovementQueryRepository;
import com.sellerdesk.inventory.repository.StockMovementRepository;
import com.sellerdesk.inventory.repository.StockRepository;
import com.sellerdesk.product.model.Product;
import com.sellerdesk.product.model.ProductStatus;
import com.sellerdesk.product.model.Variant;
import com.sellerdesk.product.model.VariantStock;
import com.sellerdesk.shop.model.ShopSettings;
import com.sellerdesk.shop.service.ShopSettingsLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;

/**
 * Business rules of {@link InventoryService} with mocked repositories. The shop always comes from
 * {@link CurrentUser} ("shopA"); every repository call must receive exactly that shop.
 */
class InventoryServiceTest {

    private static final String SHOP = new ObjectId().toHexString();
    private static final String USER = new ObjectId().toHexString();
    private static final String PRODUCT = new ObjectId().toHexString();
    private static final String VARIANT = new ObjectId().toHexString();
    private static final String REQUEST = "3F2C1E9A-8B7D-4C6E-9F00-1A2B3C4D5E6F";
    private static final String REQUEST_KEY = REQUEST.toLowerCase();
    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00Z");

    private final StockRepository stockRepository = mock(StockRepository.class);
    private final StockMovementRepository movementRepository = mock(StockMovementRepository.class);
    private final StockMovementQueryRepository movementQueryRepository = mock(StockMovementQueryRepository.class);
    private final ShopSettingsLookup shopSettingsLookup = mock(ShopSettingsLookup.class);
    private final UserService userService = mock(UserService.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final TransactionRunner transactionRunner = mock(TransactionRunner.class);

    private final InventoryService service = new InventoryService(stockRepository, movementRepository,
            movementQueryRepository, shopSettingsLookup, userService, currentUser, transactionRunner,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void loggedInAsShopA() {
        when(currentUser.shopId()).thenReturn(SHOP);
        when(currentUser.userId()).thenReturn(USER);
        User user = new User();
        user.setName("Rahim");
        when(userService.findInShop(USER, SHOP)).thenReturn(Optional.of(user));
        when(shopSettingsLookup.forShop(SHOP)).thenReturn(new ShopSettings(6000, 12000, 5, "SD"));
        // No real transaction in a unit test: just run the block.
        when(transactionRunner.run(any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(0)).get());
        when(movementRepository.insert(any(StockMovement.class))).thenAnswer(inv -> {
            StockMovement m = inv.getArgument(0);
            m.setId("m1");
            return m;
        });
    }

    private static Product product(int onHand, int reserved, Integer ownThreshold) {
        Variant variant = new Variant();
        variant.setVariantId(VARIANT);
        variant.setSku("TEE-RED-M");
        variant.setName("Red / M");
        variant.setActive(true);
        variant.setLowStockThreshold(ownThreshold);
        variant.setStock(new VariantStock(onHand, reserved, onHand - reserved));
        Product product = new Product();
        product.setId(PRODUCT);
        product.setShopId(SHOP);
        product.setName("Tee");
        product.setStatus(ProductStatus.ACTIVE);
        product.setVariants(List.of(variant));
        return product;
    }

    /** Criteria → plain Document, to compare conditions. */
    private static Document doc(Criteria criteria) {
        return criteria == null ? null : criteria.getCriteriaObject();
    }

    // ==== Restock ========================================================================

    @Nested
    class Restock {

        @Test
        void firstStockInIsInitialAndWritesMovementWithBalancesAfter() {
            when(movementRepository.existsByShopIdAndVariantId(SHOP, VARIANT)).thenReturn(false);
            when(stockRepository.applyChange(eq(SHOP), eq(VARIANT), any(), eq(10), eq(0)))
                    .thenReturn(Optional.of(product(10, 0, null)));

            StockChangeResponse response = service.restock(VARIANT, new RestockRequest(10, "  Delivery #1 ", REQUEST));

            assertThat(response.movement().type()).isEqualTo(MovementType.INITIAL);
            assertThat(response.stock().onHand()).isEqualTo(10);
            ArgumentCaptor<StockMovement> saved = ArgumentCaptor.forClass(StockMovement.class);
            verify(movementRepository).insert(saved.capture());
            StockMovement m = saved.getValue();
            assertThat(m.getShopId()).isEqualTo(SHOP);
            assertThat(m.getProductId()).isEqualTo(PRODUCT);
            assertThat(m.getSku()).isEqualTo("TEE-RED-M");
            assertThat(m.getVariantName()).isEqualTo("Red / M");
            assertThat(m.getOnHandDelta()).isEqualTo(10);
            assertThat(m.getReservedDelta()).isZero();
            assertThat(m.getOnHandAfter()).isEqualTo(10);
            assertThat(m.getAvailableAfter()).isEqualTo(10);
            assertThat(m.getNote()).isEqualTo("Delivery #1");
            assertThat(m.getRequestId()).isEqualTo(REQUEST_KEY);
            assertThat(m.getCreatedBy()).isEqualTo(USER);
            assertThat(m.getCreatedByName()).isEqualTo("Rahim");
            assertThat(m.getCreatedAt()).isEqualTo(NOW);
            assertThat(m.getReasonCode()).isNull();
        }

        @Test
        void laterStockInIsRestockAndConditionCapsOnHand() {
            when(movementRepository.existsByShopIdAndVariantId(SHOP, VARIANT)).thenReturn(true);
            ArgumentCaptor<Criteria> condition = ArgumentCaptor.forClass(Criteria.class);
            when(stockRepository.applyChange(eq(SHOP), eq(VARIANT), condition.capture(), eq(5), eq(0)))
                    .thenReturn(Optional.of(product(15, 0, null)));

            assertThat(service.restock(VARIANT, new RestockRequest(5, null, REQUEST)).movement().type())
                    .isEqualTo(MovementType.RESTOCK);
            assertThat(doc(condition.getValue()))
                    .isEqualTo(new Document("stock.onHand", new Document("$lte", InventoryRules.MAX_ON_HAND - 5)));
        }

        @Test
        void aboveTheOnHandLimitIs409StockLimitExceeded() {
            when(stockRepository.applyChange(any(), any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
            when(stockRepository.findByVariant(SHOP, VARIANT)).thenReturn(Optional.of(product(InventoryRules.MAX_ON_HAND, 0, null)));

            assertThatThrownBy(() -> service.restock(VARIANT, new RestockRequest(1, null, REQUEST)))
                    .isInstanceOf(AppException.class)
                    .extracting("code").isEqualTo(InventoryErrors.STOCK_LIMIT_EXCEEDED);
            verify(movementRepository, never()).insert(any());
        }

        @Test
        void sameRequestIdReturnsTheFirstResultWithoutChangingStock() {
            StockMovement earlier = movement(MovementType.RESTOCK, 10, null);
            when(movementRepository.findByShopIdAndRequestId(SHOP, REQUEST_KEY)).thenReturn(Optional.of(earlier));
            when(stockRepository.findByVariant(SHOP, VARIANT)).thenReturn(Optional.of(product(12, 2, null)));

            StockChangeResponse response = service.restock(VARIANT, new RestockRequest(10, null, REQUEST));

            assertThat(response.movement().id()).isEqualTo("earlier");
            assertThat(response.stock().available()).isEqualTo(10);
            verify(stockRepository, never()).applyChange(any(), any(), any(), anyInt(), anyInt());
            verify(movementRepository, never()).insert(any());
        }

        @Test
        void sameRequestIdForADifferentChangeIs409() {
            when(movementRepository.findByShopIdAndRequestId(SHOP, REQUEST_KEY))
                    .thenReturn(Optional.of(movement(MovementType.RESTOCK, 10, null)));

            assertThatThrownBy(() -> service.restock(VARIANT, new RestockRequest(11, null, REQUEST)))
                    .extracting("code").isEqualTo(InventoryErrors.REQUEST_ID_REUSED);
            assertThatThrownBy(() -> service.adjust(VARIANT,
                    new AdjustmentRequest(10, AdjustmentReason.FOUND, null, REQUEST)))
                    .extracting("code").isEqualTo(InventoryErrors.REQUEST_ID_REUSED);
        }

        @Test
        void parallelDuplicateCaughtByUniqueIndexIsAnsweredWithTheStoredMovement() {
            when(movementRepository.findByShopIdAndRequestId(SHOP, REQUEST_KEY))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(movement(MovementType.INITIAL, 10, null)));
            when(stockRepository.applyChange(any(), any(), any(), anyInt(), anyInt()))
                    .thenReturn(Optional.of(product(10, 0, null)));
            when(movementRepository.insert(any())).thenThrow(new DuplicateKeyException("E11000 requestId"));
            when(stockRepository.findByVariant(SHOP, VARIANT)).thenReturn(Optional.of(product(10, 0, null)));

            assertThat(service.restock(VARIANT, new RestockRequest(10, null, REQUEST)).movement().id()).isEqualTo("earlier");
        }

        @Test
        void unknownOrOtherShopsVariantIs404() {
            when(stockRepository.applyChange(any(), any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
            when(stockRepository.findByVariant(SHOP, VARIANT)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.restock(VARIANT, new RestockRequest(1, null, REQUEST)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .extracting("code").isEqualTo("NOT_FOUND");
            assertThatThrownBy(() -> service.restock("not-an-id", new RestockRequest(1, null, REQUEST)))
                    .isInstanceOf(ResourceNotFoundException.class);
            // The shop passed to the repository is always the logged-in one.
            verify(stockRepository).applyChange(eq(SHOP), eq(VARIANT), any(), eq(1), eq(0));
        }
    }

    // ==== Adjustment =====================================================================

    @Nested
    class Adjustment {

        @Test
        void decreaseNeedsAvailableUnitsAndIsRecordedWithReason() {
            ArgumentCaptor<Criteria> condition = ArgumentCaptor.forClass(Criteria.class);
            when(stockRepository.applyChange(eq(SHOP), eq(VARIANT), condition.capture(), eq(-2), eq(0)))
                    .thenReturn(Optional.of(product(3, 1, null)));

            StockChangeResponse response = service.adjust(VARIANT,
                    new AdjustmentRequest(-2, AdjustmentReason.DAMAGED, "wet", REQUEST));

            assertThat(doc(condition.getValue())).isEqualTo(new Document("stock.available", new Document("$gte", 2)));
            assertThat(response.movement().type()).isEqualTo(MovementType.ADJUSTMENT);
            assertThat(response.movement().onHandDelta()).isEqualTo(-2);
            assertThat(response.movement().reasonCode()).isEqualTo(AdjustmentReason.DAMAGED);
            assertThat(response.movement().note()).isEqualTo("wet");
            assertThat(response.stock()).extracting("onHand", "reserved", "available").containsExactly(3, 1, 2);
            // An adjustment never asks whether it is the first stock-in.
            verify(movementRepository, never()).existsByShopIdAndVariantId(anyString(), anyString());
        }

        @Test
        void decreaseBelowAvailableIs409InsufficientStockWithSkuAndNumbers() {
            when(stockRepository.applyChange(any(), any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
            when(stockRepository.findByVariant(SHOP, VARIANT)).thenReturn(Optional.of(product(5, 3, null)));

            assertThatThrownBy(() -> service.adjust(VARIANT,
                    new AdjustmentRequest(-4, AdjustmentReason.LOST, null, REQUEST)))
                    .isInstanceOf(AppException.class)
                    .hasMessage("Not enough stock for SKU TEE-RED-M: requested 4, available 2")
                    .extracting("code").isEqualTo(InventoryErrors.INSUFFICIENT_STOCK);
            verify(movementRepository, never()).insert(any());
        }

        @Test
        void zeroChangeIs400() {
            assertThatThrownBy(() -> service.adjust(VARIANT, new AdjustmentRequest(0, AdjustmentReason.FOUND, null, REQUEST)))
                    .isInstanceOf(FieldValidationException.class)
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).field()).isEqualTo("quantityChange"));
        }

        @Test
        void otherWithoutNoteIs400() {
            assertThatThrownBy(() -> service.adjust(VARIANT, new AdjustmentRequest(1, AdjustmentReason.OTHER, "  ", REQUEST)))
                    .isInstanceOf(FieldValidationException.class)
                    .satisfies(e -> assertThat(((AppException) e).getFieldErrors().get(0).field()).isEqualTo("note"));
            verify(stockRepository, never()).applyChange(any(), any(), any(), anyInt(), anyInt());
        }

        @Test
        void increaseHasNoAvailableCondition() {
            ArgumentCaptor<Criteria> condition = ArgumentCaptor.forClass(Criteria.class);
            when(stockRepository.applyChange(eq(SHOP), eq(VARIANT), condition.capture(), eq(3), eq(0)))
                    .thenReturn(Optional.of(product(3, 0, null)));
            service.adjust(VARIANT, new AdjustmentRequest(3, AdjustmentReason.FOUND, null, REQUEST));
            assertThat(doc(condition.getValue()))
                    .isEqualTo(new Document("stock.onHand", new Document("$lte", InventoryRules.MAX_ON_HAND - 3)));
        }
    }

    // ==== Order operations ===============================================================

    @Nested
    class OrderOperations {

        private final OrderRef order = new OrderRef(new ObjectId().toHexString(), "SD-1042");

        @Test
        void reserveMergesLinesOfTheSameVariantAndLinksTheOrder() {
            ArgumentCaptor<Criteria> condition = ArgumentCaptor.forClass(Criteria.class);
            when(stockRepository.applyChange(eq(SHOP), eq(VARIANT), condition.capture(), eq(0), eq(3)))
                    .thenReturn(Optional.of(product(5, 3, null)));

            List<StockMovement> movements = service.reserve(
                    List.of(new StockLine(VARIANT, 1), new StockLine(VARIANT, 2)), order);

            assertThat(movements).hasSize(1);
            StockMovement m = movements.get(0);
            assertThat(m.getType()).isEqualTo(MovementType.RESERVE);
            assertThat(m.getReservedDelta()).isEqualTo(3);
            assertThat(m.getOnHandDelta()).isZero();
            assertThat(m.getOrderId()).isEqualTo(order.orderId());
            assertThat(m.getOrderNumber()).isEqualTo("SD-1042");
            assertThat(m.getRequestId()).isNull();
            assertThat(doc(condition.getValue())).isEqualTo(new Document("stock.available", new Document("$gte", 3)));
        }

        @Test
        void reserveWithTooLittleStockIs409() {
            when(stockRepository.applyChange(any(), any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
            when(stockRepository.findByVariant(SHOP, VARIANT)).thenReturn(Optional.of(product(1, 1, null)));

            assertThatThrownBy(() -> service.reserve(List.of(new StockLine(VARIANT, 1)), order))
                    .extracting("code").isEqualTo(InventoryErrors.INSUFFICIENT_STOCK);
        }

        @Test
        void deductTakesFromOnHandAndReservedAndNeedsReservedUnits() {
            ArgumentCaptor<Criteria> condition = ArgumentCaptor.forClass(Criteria.class);
            when(stockRepository.applyChange(eq(SHOP), eq(VARIANT), condition.capture(), eq(-2), eq(-2)))
                    .thenReturn(Optional.of(product(3, 0, null)));

            StockMovement m = service.deduct(List.of(new StockLine(VARIANT, 2)), order).get(0);

            assertThat(m.getType()).isEqualTo(MovementType.DEDUCT);
            assertThat(m.getOnHandDelta()).isEqualTo(-2);
            assertThat(m.getReservedDelta()).isEqualTo(-2);
            assertThat(doc(condition.getValue())).isEqualTo(new Document("stock.reserved", new Document("$gte", 2)));
        }

        @Test
        void releaseAndReturnUseTheRightDeltas() {
            when(stockRepository.applyChange(eq(SHOP), eq(VARIANT), any(), eq(0), eq(-1)))
                    .thenReturn(Optional.of(product(5, 0, null)));
            when(stockRepository.applyChange(eq(SHOP), eq(VARIANT), any(), eq(1), eq(0)))
                    .thenReturn(Optional.of(product(6, 0, null)));

            assertThat(service.release(List.of(new StockLine(VARIANT, 1)), order).get(0).getType())
                    .isEqualTo(MovementType.RELEASE);
            assertThat(service.restockReturn(List.of(new StockLine(VARIANT, 1)), order).get(0).getType())
                    .isEqualTo(MovementType.RETURN);
        }

        @Test
        void releaseMoreThanReservedIs409WithReservedCount() {
            when(stockRepository.applyChange(any(), any(), any(), anyInt(), anyInt())).thenReturn(Optional.empty());
            when(stockRepository.findByVariant(SHOP, VARIANT)).thenReturn(Optional.of(product(5, 1, null)));

            assertThatThrownBy(() -> service.release(List.of(new StockLine(VARIANT, 2)), order))
                    .hasMessage("Not enough stock for SKU TEE-RED-M: requested 2, available 1");
        }
    }

    // ==== Lists ==========================================================================

    @Nested
    class Lists {

        @Test
        void listUsesShopDefaultWhenVariantHasNoThreshold() {
            ArgumentCaptor<StockRepository.ListQuery> query = ArgumentCaptor.forClass(StockRepository.ListQuery.class);
            when(stockRepository.list(eq(SHOP), query.capture())).thenReturn(new StockRepository.ListResult(List.of(
                    row("A-1", 4, null), row("A-2", 4, 2), row("A-3", 0, null)), 3));

            PageResponse<StockRow> page = service.list(" tee ", "ALL", false, "name,asc", 0, 20);

            assertThat(query.getValue().shopDefault()).isEqualTo(5);
            assertThat(query.getValue().skuExact()).isEqualTo("TEE");
            assertThat(query.getValue().namePrefixRegex()).isEqualTo("^tee");
            assertThat(page.content()).extracting(StockRow::lowStockThreshold).containsExactly(5, 2, 5);
            assertThat(page.content()).extracting(StockRow::thresholdSource)
                    .containsExactly(ThresholdSource.SHOP, ThresholdSource.VARIANT, ThresholdSource.SHOP);
            assertThat(page.content()).extracting(StockRow::stockStatus)
                    .containsExactly(StockStatus.LOW, StockStatus.IN_STOCK, StockStatus.OUT);
            assertThat(page.totalElements()).isEqualTo(3);
        }

        @Test
        void lowStockIsLowOrOutActiveOnlySortedByAvailable() {
            ArgumentCaptor<StockRepository.ListQuery> query = ArgumentCaptor.forClass(StockRepository.ListQuery.class);
            when(stockRepository.list(eq(SHOP), query.capture())).thenReturn(new StockRepository.ListResult(List.of(), 0));

            service.lowStock(0, 20);

            assertThat(query.getValue().status()).isEqualTo(StockRepository.StatusFilter.LOW_OR_OUT);
            assertThat(query.getValue().includeInactive()).isFalse();
            assertThat(query.getValue().sort()).isEqualTo(StockRepository.ListSort.AVAILABLE);
        }

        @Test
        void unknownSortOrStatusIs400() {
            assertThatThrownBy(() -> service.list(null, "ALL", false, "price,asc", 0, 20))
                    .isInstanceOf(FieldValidationException.class);
            assertThatThrownBy(() -> service.list(null, "SOMETIMES", false, "name,asc", 0, 20))
                    .isInstanceOf(FieldValidationException.class);
        }

        @Test
        void movementFilterWithMalformedIdIsAnEmptyPageWithoutQuery() {
            PageResponse<?> page = service.movements("nope", null, null, null, null, null, null, 0, 20);
            assertThat(page.totalElements()).isZero();
            verify(movementQueryRepository, never()).search(any(), any(), anyInt(), anyInt());
        }

        @Test
        void movementDatesAcceptDayOrInstantAndRejectGarbage() {
            when(movementQueryRepository.search(eq(SHOP), any(), anyInt(), anyInt()))
                    .thenReturn(new StockMovementQueryRepository.Result(List.of(), 0));
            ArgumentCaptor<StockMovementQueryRepository.Filter> filter =
                    ArgumentCaptor.forClass(StockMovementQueryRepository.Filter.class);

            service.movements(null, null, "RESTOCK", "DAMAGED", null, "2026-11-01", "2026-11-02T06:00:00Z", 0, 20);

            verify(movementQueryRepository).search(eq(SHOP), filter.capture(), eq(0), eq(20));
            assertThat(filter.getValue().from()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
            assertThat(filter.getValue().to()).isEqualTo(Instant.parse("2026-11-02T06:00:00Z"));
            assertThat(filter.getValue().type()).isEqualTo(MovementType.RESTOCK);
            assertThat(filter.getValue().reasonCode()).isEqualTo(AdjustmentReason.DAMAGED);
            assertThatThrownBy(() -> service.movements(null, null, null, null, null, "yesterday", null, 0, 20))
                    .isInstanceOf(FieldValidationException.class);
        }

        @Test
        void variantDetailOfUnknownVariantIs404() {
            when(stockRepository.findByVariant(SHOP, VARIANT)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.variant(VARIANT)).isInstanceOf(ResourceNotFoundException.class);
            verify(movementQueryRepository, never()).latestForVariant(any(), any(), anyInt());
        }

        @Test
        void variantDetailShowsOwnThreshold() {
            when(stockRepository.findByVariant(SHOP, VARIANT)).thenReturn(Optional.of(product(8, 0, 10)));
            when(movementQueryRepository.latestForVariant(SHOP, VARIANT, InventoryRules.RECENT_MOVEMENTS))
                    .thenReturn(List.of(movement(MovementType.INITIAL, 8, null)));

            var detail = service.variant(VARIANT);

            assertThat(detail.row().lowStockThreshold()).isEqualTo(10);
            assertThat(detail.row().thresholdSource()).isEqualTo(ThresholdSource.VARIANT);
            assertThat(detail.row().stockStatus()).isEqualTo(StockStatus.LOW);
            assertThat(detail.recentMovements()).hasSize(1);
        }

        private StockRepository.Row row(String sku, int available, Integer ownThreshold) {
            return new StockRepository.Row(PRODUCT, "Tee", ProductStatus.ACTIVE, null, VARIANT, sku, sku, true,
                    available, 0, available, ownThreshold);
        }
    }

    private static StockMovement movement(MovementType type, int onHandDelta, AdjustmentReason reason) {
        StockMovement m = new StockMovement();
        m.setId("earlier");
        m.setShopId(SHOP);
        m.setProductId(PRODUCT);
        m.setVariantId(VARIANT);
        m.setSku("TEE-RED-M");
        m.setType(type);
        m.setOnHandDelta(onHandDelta);
        m.setOnHandAfter(onHandDelta);
        m.setAvailableAfter(onHandDelta);
        m.setReasonCode(reason);
        m.setRequestId(REQUEST_KEY);
        m.setCreatedBy(USER);
        m.setCreatedByName("Rahim");
        m.setCreatedAt(NOW);
        return m;
    }
}
