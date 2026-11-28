package com.sellerdesk.inventory.service;

import com.sellerdesk.auth.model.User;
import com.sellerdesk.auth.service.UserService;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.transaction.TransactionRunner;
import com.sellerdesk.inventory.dto.AdjustmentRequest;
import com.sellerdesk.inventory.dto.MovementResponse;
import com.sellerdesk.inventory.dto.RestockRequest;
import com.sellerdesk.inventory.dto.StockChangeResponse;
import com.sellerdesk.inventory.dto.StockLevels;
import com.sellerdesk.inventory.dto.StockRow;
import com.sellerdesk.inventory.dto.VariantStockDetail;
import com.sellerdesk.inventory.model.AdjustmentReason;
import com.sellerdesk.inventory.model.MovementType;
import com.sellerdesk.inventory.model.StockMovement;
import com.sellerdesk.inventory.repository.StockMovementQueryRepository;
import com.sellerdesk.inventory.repository.StockMovementRepository;
import com.sellerdesk.inventory.repository.StockRepository;
import com.sellerdesk.inventory.repository.StockRepository.ListQuery;
import com.sellerdesk.inventory.repository.StockRepository.ListSort;
import com.sellerdesk.inventory.repository.StockRepository.StatusFilter;
import com.sellerdesk.product.model.Product;
import com.sellerdesk.product.model.Variant;
import com.sellerdesk.product.model.VariantStock;
import com.sellerdesk.product.service.ProductRules;
import com.sellerdesk.shop.service.ShopSettingsLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock levels and the movement log (docs/modules/INVENTORY.md). The only class that changes
 * {@code products.variants[].stock}.
 *
 * <p><b>Rules every write follows</b>
 * <ol>
 *   <li>The shop is always {@link CurrentUser#shopId()} (the JWT), never request data. A variant of
 *       another shop is "not found" (404).</li>
 *   <li>The stock change is one conditional {@code findAndModify} ({@link StockRepository#applyChange}):
 *       check and change in one step, so stock never goes negative, even with parallel requests.</li>
 *   <li>The stock change and its movement are written in <b>one transaction</b>, so there is never a
 *       change without a log entry (or a log entry without a change).</li>
 * </ol>
 *
 * <p>Restock and adjustment open their own transaction through {@link TransactionRunner} (which
 * retries on write conflicts). The order operations ({@link #reserve}, {@link #release},
 * {@link #deduct}, {@link #restockReturn}) must run <b>inside the Order module's transaction</b>
 * ({@code MANDATORY}), so the order status and the stock of all its items change together.
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final StockRepository stockRepository;
    private final StockMovementRepository movementRepository;
    private final StockMovementQueryRepository movementQueryRepository;
    private final ShopSettingsLookup shopSettingsLookup;
    private final UserService userService;
    private final CurrentUser currentUser;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    // ==== Read ===========================================================================

    /**
     * @param stockStatus {@code ALL} (default), {@code LOW}, {@code OUT} or {@code LOW_OR_OUT}
     * @param sort        {@code name,asc} (default) or {@code available,asc}
     */
    public PageResponse<StockRow> list(String q, String stockStatus, boolean includeInactive, String sort,
                                       int page, int size) {
        return list(q, parseStatus(stockStatus), includeInactive, parseSort(sort), page, size);
    }

    /** Low and sold-out variants of active products, lowest {@code available} first (dashboard). */
    public PageResponse<StockRow> lowStock(int page, int size) {
        return list(null, StatusFilter.LOW_OR_OUT, false, ListSort.AVAILABLE, page, size);
    }

    public VariantStockDetail variant(String variantId) {
        String shopId = currentUser.shopId();
        requireValidId(variantId);
        Product product = stockRepository.findByVariant(shopId, variantId)
                .orElseThrow(InventoryErrors::variantNotFound);
        Variant variant = findVariant(product, variantId);
        int shopDefault = shopDefaultThreshold(shopId);
        StockRow row = toRow(new StockRepository.Row(product.getId(), product.getName(), product.getStatus(),
                product.getImages().isEmpty() ? null : product.getImages().get(0).getUrl(),
                variant.getVariantId(), variant.getSku(), variant.getName(), variant.isActive(),
                stockOf(variant).onHand(), stockOf(variant).reserved(), stockOf(variant).available(),
                variant.getLowStockThreshold()), shopDefault);
        List<MovementResponse> recent = movementQueryRepository
                .latestForVariant(shopId, variantId, InventoryRules.RECENT_MOVEMENTS).stream()
                .map(InventoryService::toResponse)
                .toList();
        return new VariantStockDetail(row, recent);
    }

    /**
     * Movement history, newest first. An id filter that is not a valid id, or that belongs to
     * another shop, simply matches nothing (empty page): lists filter, they do not look anything up.
     *
     * @param from inclusive, ISO date ({@code 2026-11-01}, start of day UTC) or instant
     * @param to   exclusive, same formats
     */
    public PageResponse<MovementResponse> movements(String variantId, String productId, String type,
                                                    String reasonCode, String orderId, String from, String to,
                                                    int page, int size) {
        String shopId = currentUser.shopId();
        StockMovementQueryRepository.Filter filter = new StockMovementQueryRepository.Filter(
                blankToNull(variantId), blankToNull(productId),
                parseEnum(MovementType.class, type, "type"),
                parseEnum(AdjustmentReason.class, reasonCode, "reasonCode"),
                blankToNull(orderId),
                parseInstant(from, "from"), parseInstant(to, "to"));
        for (String id : new String[] {filter.variantId(), filter.productId(), filter.orderId()}) {
            if (id != null && !ObjectId.isValid(id)) {
                return PageResponse.of(List.of(), page, size, 0);
            }
        }
        StockMovementQueryRepository.Result result = movementQueryRepository.search(shopId, filter, page, size);
        return PageResponse.of(result.content().stream().map(InventoryService::toResponse).toList(),
                page, size, result.totalElements());
    }

    // ==== Manual writes ==================================================================

    /**
     * Adds stock. The first stock-in of a variant is recorded as {@code INITIAL}, every later one
     * as {@code RESTOCK}. A repeated {@code requestId} returns the first result unchanged.
     */
    public StockChangeResponse restock(String variantId, RestockRequest request) {
        return applyManual(variantId, request.requestId(),
                new ManualChange(true, request.quantity(), null, normalizeNote(request.note())));
    }

    /**
     * Manual correction, up or down. A decrease may only remove unreserved units
     * ({@code available}), so confirmed orders always keep their stock.
     */
    public StockChangeResponse adjust(String variantId, AdjustmentRequest request) {
        if (request.quantityChange() == 0) {
            throw new FieldValidationException("quantityChange", "must not be 0");
        }
        String note = normalizeNote(request.note());
        if (request.reasonCode() == AdjustmentReason.OTHER && note == null) {
            throw new FieldValidationException("note", "is required when reasonCode is OTHER");
        }
        return applyManual(variantId, request.requestId(),
                new ManualChange(false, request.quantityChange(), request.reasonCode(), note));
    }

    /** What a restock or adjustment asks for. {@code stockIn}: restock (INITIAL / RESTOCK). */
    private record ManualChange(boolean stockIn, int onHandDelta, AdjustmentReason reasonCode, String note) {
    }

    private StockChangeResponse applyManual(String variantId, String requestId, ManualChange change) {
        String shopId = currentUser.shopId();
        String userId = currentUser.userId();
        requireValidId(variantId);
        String requestKey = requestId.toLowerCase(Locale.ROOT);
        String userName = userName(shopId, userId);
        try {
            return transactionRunner.run(() -> {
                // 1. Same request again (double tap, retry after a timeout)? Answer as the first time.
                Optional<StockMovement> earlier = movementRepository.findByShopIdAndRequestId(shopId, requestKey);
                if (earlier.isPresent()) {
                    return replay(shopId, earlier.get(), variantId, change);
                }
                // 2. INITIAL or RESTOCK. Race-free: two parallel "first" restocks both write the same
                //    product below, so one gets a write conflict, is retried, and then sees a movement.
                MovementType type = !change.stockIn() ? MovementType.ADJUSTMENT
                        : movementRepository.existsByShopIdAndVariantId(shopId, variantId)
                                ? MovementType.RESTOCK : MovementType.INITIAL;
                // 3. Conditional stock change. 4. The movement, in the same transaction.
                Product updated = applyStock(shopId, variantId, change.onHandDelta(), 0);
                StockMovement movement = newMovement(shopId, updated, variantId, type, change.onHandDelta(), 0,
                        userId, userName);
                movement.setReasonCode(change.reasonCode());
                movement.setNote(change.note());
                movement.setRequestId(requestKey);
                StockMovement saved = movementRepository.insert(movement);
                return new StockChangeResponse(toResponse(saved), afterLevels(saved));
            });
        } catch (DuplicateKeyException e) {
            // A parallel request with the same requestId committed first; the unique index stopped us.
            StockMovement earlier = movementRepository.findByShopIdAndRequestId(shopId, requestKey).orElseThrow(() -> e);
            return replay(shopId, earlier, variantId, change);
        }
    }

    /** The stored answer for a repeated requestId, or 409 if the id was used for something else. */
    private StockChangeResponse replay(String shopId, StockMovement earlier, String variantId, ManualChange change) {
        boolean sameKind = change.stockIn()
                ? earlier.getType() == MovementType.INITIAL || earlier.getType() == MovementType.RESTOCK
                : earlier.getType() == MovementType.ADJUSTMENT && earlier.getReasonCode() == change.reasonCode();
        if (!sameKind || !earlier.getVariantId().equals(variantId) || earlier.getOnHandDelta() != change.onHandDelta()) {
            throw InventoryErrors.requestIdReused();
        }
        StockLevels now = stockRepository.findByVariant(shopId, variantId)
                .map(p -> levels(stockOf(findVariant(p, variantId))))
                .orElseGet(() -> afterLevels(earlier));
        return new StockChangeResponse(toResponse(earlier), now);
    }

    /**
     * Current {@code available} of each variant (read only), for the order screen and the
     * "not enough stock" report of a failed confirm. Malformed ids, unknown ids and ids of another
     * shop are simply missing from the result.
     */
    public Map<String, Integer> availableFor(Collection<String> variantIds) {
        String shopId = currentUser.shopId();
        List<String> valid = variantIds.stream().filter(id -> id != null && ObjectId.isValid(id)).distinct().toList();
        Map<String, Integer> result = new LinkedHashMap<>();
        if (valid.isEmpty()) {
            return result;
        }
        for (Product product : stockRepository.findStockOfVariants(shopId, valid)) {
            for (Variant v : product.getVariants()) {
                if (valid.contains(v.getVariantId())) {
                    result.put(v.getVariantId(), stockOf(v).available());
                }
            }
        }
        return result;
    }

    // ==== Order operations (Java API for the Order module) ================================

    /** Order → CONFIRMED: hold stock for each item. All items or none ({@code INSUFFICIENT_STOCK}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockMovement> reserve(List<StockLine> lines, OrderRef order) {
        return orderMovements(MovementType.RESERVE, lines, order);
    }

    /** CONFIRMED / PACKED order → CANCELLED: give the held stock back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockMovement> release(List<StockLine> lines, OrderRef order) {
        return orderMovements(MovementType.RELEASE, lines, order);
    }

    /** Order → SHIPPED: the held units leave the shelf. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockMovement> deduct(List<StockLine> lines, OrderRef order) {
        return orderMovements(MovementType.DEDUCT, lines, order);
    }

    /** Order → RETURNED: the items chosen for restock go back on the shelf. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockMovement> restockReturn(List<StockLine> lines, OrderRef order) {
        return orderMovements(MovementType.RETURN, lines, order);
    }

    private List<StockMovement> orderMovements(MovementType type, List<StockLine> lines, OrderRef order) {
        String shopId = currentUser.shopId();
        String userId = currentUser.userId();
        String userName = userName(shopId, userId);
        List<StockMovement> written = new ArrayList<>();
        // The same variant twice in one order → one update with the summed quantity.
        for (Map.Entry<String, Integer> line : merge(lines).entrySet()) {
            String variantId = line.getKey();
            int q = line.getValue();
            int onHandDelta = switch (type) {
                case DEDUCT -> -q;
                case RETURN -> q;
                default -> 0;
            };
            int reservedDelta = switch (type) {
                case RESERVE -> q;
                case RELEASE, DEDUCT -> -q;
                default -> 0;
            };
            Product updated = applyStock(shopId, variantId, onHandDelta, reservedDelta);
            StockMovement movement = newMovement(shopId, updated, variantId, type, onHandDelta, reservedDelta,
                    userId, userName);
            movement.setOrderId(order.orderId());
            movement.setOrderNumber(order.orderNumber());
            written.add(movementRepository.insert(movement));
        }
        return written;
    }

    private static Map<String, Integer> merge(List<StockLine> lines) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (StockLine line : lines) {
            if (line.quantity() < 1) {
                throw new IllegalArgumentException("Order line quantity must be at least 1");
            }
            requireValidId(line.variantId());
            merged.merge(line.variantId(), line.quantity(), Integer::sum);
        }
        return merged;
    }

    // ==== Shared write helpers ===========================================================

    /**
     * The conditional {@code $inc}. Conditions follow from the deltas: a decrease of
     * {@code available} needs that many available units, a decrease of {@code reserved} needs that
     * many reserved units, an increase of {@code onHand} must stay ≤ {@link InventoryRules#MAX_ON_HAND}.
     * No match → re-read to say why: 404, {@code INSUFFICIENT_STOCK} or {@code STOCK_LIMIT_EXCEEDED}.
     */
    private Product applyStock(String shopId, String variantId, int onHandDelta, int reservedDelta) {
        int availableDelta = onHandDelta - reservedDelta;
        List<Criteria> conditions = new ArrayList<>();
        if (availableDelta < 0) {
            conditions.add(Criteria.where("stock.available").gte(-availableDelta));
        }
        if (reservedDelta < 0) {
            conditions.add(Criteria.where("stock.reserved").gte(-reservedDelta));
        }
        if (onHandDelta > 0) {
            conditions.add(Criteria.where("stock.onHand").lte(InventoryRules.MAX_ON_HAND - onHandDelta));
        }
        Criteria condition = conditions.isEmpty() ? null
                : conditions.size() == 1 ? conditions.get(0) : new Criteria().andOperator(conditions);
        return stockRepository.applyChange(shopId, variantId, condition, onHandDelta, reservedDelta)
                .orElseThrow(() -> changeFailure(shopId, variantId, onHandDelta, reservedDelta));
    }

    private RuntimeException changeFailure(String shopId, String variantId, int onHandDelta, int reservedDelta) {
        Optional<Product> product = stockRepository.findByVariant(shopId, variantId);
        if (product.isEmpty()) {
            return InventoryErrors.variantNotFound();
        }
        Variant variant = findVariant(product.get(), variantId);
        VariantStock stock = stockOf(variant);
        if (onHandDelta > 0 && (long) stock.onHand() + onHandDelta > InventoryRules.MAX_ON_HAND) {
            return InventoryErrors.stockLimitExceeded(variant.getSku());
        }
        if (reservedDelta < 0) {
            return InventoryErrors.insufficientStock(variant.getSku(), -reservedDelta, stock.reserved());
        }
        return InventoryErrors.insufficientStock(variant.getSku(), -(onHandDelta - reservedDelta), stock.available());
    }

    private StockMovement newMovement(String shopId, Product updated, String variantId, MovementType type,
                                      int onHandDelta, int reservedDelta, String userId, String userName) {
        Variant variant = findVariant(updated, variantId);
        VariantStock after = stockOf(variant);
        StockMovement m = new StockMovement();
        m.setShopId(shopId);
        m.setProductId(updated.getId());
        m.setVariantId(variantId);
        m.setSku(variant.getSku());
        m.setVariantName(variant.getName());
        m.setType(type);
        m.setOnHandDelta(onHandDelta);
        m.setReservedDelta(reservedDelta);
        m.setOnHandAfter(after.onHand());
        m.setReservedAfter(after.reserved());
        m.setAvailableAfter(after.available());
        m.setCreatedBy(userId);
        m.setCreatedByName(userName);
        m.setCreatedAt(Instant.now(clock));
        return m;
    }

    private String userName(String shopId, String userId) {
        return userService.findInShop(userId, shopId).map(User::getName).orElse("Unknown user");
    }

    // ==== Read helpers ===================================================================

    private PageResponse<StockRow> list(String q, StatusFilter status, boolean includeInactive, ListSort sort,
                                        int page, int size) {
        String shopId = currentUser.shopId();
        int shopDefault = shopDefaultThreshold(shopId);
        String text = ProductRules.normalizeText(q);
        ListQuery query = new ListQuery(
                text == null ? null : ProductRules.normalizeSku(text),
                text == null ? null : ProductRules.startsWithRegex(ProductRules.lower(text)),
                status, includeInactive, sort, shopDefault, page, size);
        StockRepository.ListResult result = stockRepository.list(shopId, query);
        return PageResponse.of(result.rows().stream().map(r -> toRow(r, shopDefault)).toList(),
                page, size, result.totalElements());
    }

    /** Cached per shop ({@link ShopSettingsLookup}); evicted when the shop settings change. */
    private int shopDefaultThreshold(String shopId) {
        return shopSettingsLookup.forShop(shopId).defaultLowStockThreshold();
    }

    private static StockRow toRow(StockRepository.Row r, int shopDefault) {
        int threshold = StockStatusCalculator.effectiveThreshold(r.ownThreshold(), shopDefault);
        return new StockRow(r.productId(), r.productName(), r.coverImageUrl(), r.productStatus(), r.variantId(),
                r.sku(), r.variantName(), r.active(), r.onHand(), r.reserved(), r.available(), threshold,
                StockStatusCalculator.source(r.ownThreshold()),
                StockStatusCalculator.status(r.available(), threshold));
    }

    static MovementResponse toResponse(StockMovement m) {
        return new MovementResponse(m.getId(), m.getProductId(), m.getVariantId(), m.getSku(), m.getVariantName(),
                m.getType(), m.getOnHandDelta(), m.getReservedDelta(), m.getOnHandAfter(), m.getReservedAfter(),
                m.getAvailableAfter(), m.getReasonCode(), m.getNote(), m.getOrderId(), m.getOrderNumber(),
                new MovementResponse.Actor(m.getCreatedBy(), m.getCreatedByName()), m.getCreatedAt());
    }

    private static StockLevels afterLevels(StockMovement m) {
        return new StockLevels(m.getOnHandAfter(), m.getReservedAfter(), m.getAvailableAfter());
    }

    private static StockLevels levels(VariantStock s) {
        return new StockLevels(s.onHand(), s.reserved(), s.available());
    }

    private static VariantStock stockOf(Variant variant) {
        return variant.getStock() != null ? variant.getStock() : VariantStock.empty();
    }

    private static Variant findVariant(Product product, String variantId) {
        return product.getVariants().stream()
                .filter(v -> variantId.equals(v.getVariantId()))
                .findFirst()
                .orElseThrow(InventoryErrors::variantNotFound);
    }

    /** A malformed id cannot exist in any shop: 404, like an unknown one. */
    private static void requireValidId(String variantId) {
        if (variantId == null || !ObjectId.isValid(variantId)) {
            throw InventoryErrors.variantNotFound();
        }
    }

    private static String normalizeNote(String note) {
        return note == null || note.isBlank() ? null : note.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static StatusFilter parseStatus(String value) {
        StatusFilter status = parseEnum(StatusFilter.class, value, "stockStatus");
        return status == null ? StatusFilter.ALL : status;
    }

    private static ListSort parseSort(String value) {
        if (value == null || value.isBlank() || "name,asc".equals(value)) {
            return ListSort.NAME;
        }
        if ("available,asc".equals(value)) {
            return ListSort.AVAILABLE;
        }
        throw new FieldValidationException("sort", "must be name,asc or available,asc");
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException(field, "unknown value " + value);
        }
    }

    private static Instant parseInstant(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return value.length() == 10
                    ? LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant()
                    : Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new FieldValidationException(field, "must be a date like 2026-11-01 or 2026-11-01T10:00:00Z");
        }
    }
}
