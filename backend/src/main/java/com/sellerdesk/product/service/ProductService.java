package com.sellerdesk.product.service;

import com.sellerdesk.common.config.CacheConfig;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.common.error.FieldValidationException;
import com.sellerdesk.common.security.CurrentUser;
import com.sellerdesk.common.storage.ImageCleanupTasks;
import com.sellerdesk.common.storage.ImagesRemovedEvent;
import com.sellerdesk.product.dto.AddVariantRequest;
import com.sellerdesk.product.dto.CategoryResponse;
import com.sellerdesk.product.dto.CreateProductRequest;
import com.sellerdesk.product.dto.ProductLookupItem;
import com.sellerdesk.product.dto.ProductResponse;
import com.sellerdesk.product.dto.ProductStatusRequest;
import com.sellerdesk.product.dto.ProductSummary;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Products and their variants (docs/modules/PRODUCT.md). Images: {@link ProductImageService}.
 *
 * <p>The shop always comes from {@link CurrentUser#shopId()} (the JWT), never from the request,
 * and is passed to every repository call. A product of another shop is "not found" (404).
 *
 * <p>After create, every change is a targeted update with the condition
 * {@code version == <the version the client loaded>}; see {@link ProductQueryRepository}.
 * When the update matches nothing, the product is re-read to give the right error:
 * gone → 404, version changed → 409 CONCURRENT_MODIFICATION.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    static final String DEFAULT_SORT = ProductSort.UPDATED_DESC.param();

    private final ProductRepository productRepository;
    private final ProductQueryRepository queryRepository;
    private final ProductMapper mapper;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;
    private final ImageCleanupTasks cleanupTasks;
    /** Empty until Inventory / Order provide implementations. */
    private final List<ProductUsageChecker> usageCheckers;
    private final Clock clock;

    // ==== Read ===========================================================================

    public ProductResponse get(String productId) {
        return mapper.toResponse(load(currentUser.shopId(), productId));
    }

    /**
     * @param status {@code ACTIVE} (default), {@code ARCHIVED} or {@code ALL}
     * @param sort   one of {@link ProductSort}; default {@code updatedAt,desc}
     */
    public PageResponse<ProductSummary> list(String q, String category, String status, String sort, int page, int size) {
        String shopId = currentUser.shopId();
        ProductSort productSort = ProductSort.fromParam(sort == null ? DEFAULT_SORT : sort)
                .orElseThrow(() -> new FieldValidationException("sort", "must be one of " + ProductSort.PARAM_REGEX));
        String text = ProductRules.normalizeText(q);
        ProductQueryRepository.Search search = new ProductQueryRepository.Search(
                text == null ? null : ProductRules.normalizeSku(text),
                text == null ? null : ProductRules.startsWithRegex(ProductRules.lower(text)),
                ProductRules.lower(ProductRules.normalizeText(category)),
                parseStatusFilter(status),
                productSort, page, size);
        ProductQueryRepository.SearchResult result = queryRepository.search(shopId, search);
        return PageResponse.of(result.content().stream().map(mapper::toSummary).toList(),
                page, size, result.totalElements());
    }

    /**
     * Active products with their active variants, for the order form. Cached per shop
     * ({@link CacheConfig#PRODUCT_LOOKUP}); {@link ProductLookupCacheEvictor} drops the entry on
     * every product change. The cache key is the shop from the JWT, read the same way as
     * {@link CurrentUser#shopId()}.
     */
    @Cacheable(cacheNames = CacheConfig.PRODUCT_LOOKUP,
            key = "T(com.sellerdesk.common.security.TenantContext).requireShopId()")
    public List<ProductLookupItem> lookup() {
        String shopId = currentUser.shopId();
        return queryRepository.findActiveForLookup(shopId).stream()
                .map(mapper::toLookupItem)
                .flatMap(Optional::stream)
                .toList();
    }

    public List<CategoryResponse> categories() {
        return queryRepository.categories(currentUser.shopId()).stream()
                .map(c -> new CategoryResponse(c.name(), c.productCount()))
                .toList();
    }

    // ==== Create =========================================================================

    public ProductResponse create(CreateProductRequest request) {
        String shopId = currentUser.shopId();

        // SKU → field path, in request order. MongoDB's unique index cannot see duplicates
        // inside one document, so they are checked here.
        Map<String, String> fieldBySku = new LinkedHashMap<>();
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < request.variants().size(); i++) {
            VariantRequest v = request.variants().get(i);
            String sku = ProductRules.normalizeSku(v.sku());
            String field = "variants[" + i + "].sku";
            if (fieldBySku.putIfAbsent(sku, field) != null) {
                throw new FieldValidationException(field, "SKU " + sku + " is used twice in this product");
            }
            variants.add(newVariant(v, sku));
        }
        requireUniqueAttributes(variants);
        requireSkusFree(shopId, fieldBySku, null);

        Product product = new Product();
        product.setShopId(shopId);
        product.setCreatedBy(currentUser.userId());
        product.setStatus(ProductStatus.ACTIVE);
        applyProductFields(product, request.name(), request.description(), request.category());
        product.setVariants(variants);
        Long[] range = ProductRules.priceRange(variants);
        product.setMinPrice(range[0]);
        product.setMaxPrice(range[1]);

        Product saved;
        try {
            saved = productRepository.save(product);
        } catch (DuplicateKeyException e) {
            // Another request took one of the SKUs between our check and the insert.
            throw skuConflict(shopId, fieldBySku, null);
        }
        publishChanged(shopId, saved.getId());
        return mapper.toResponse(saved);
    }

    // ==== Update product fields / status =================================================

    public ProductResponse update(String productId, UpdateProductRequest request) {
        String shopId = currentUser.shopId();
        Product values = new Product();
        applyProductFields(values, request.name(), request.description(), request.category());

        Update update = new Update()
                .set("name", values.getName())
                .set("nameLower", values.getNameLower());
        setOrUnset(update, "description", values.getDescription());
        setOrUnset(update, "category", values.getCategory());
        setOrUnset(update, "categoryKey", values.getCategoryKey());
        return apply(shopId, productId, request.version(), null, update,
                () -> notFoundOrStale(shopId, productId));
    }

    /** Archive ({@code ARCHIVED}) or unarchive ({@code ACTIVE}). Archived products stay editable. */
    public ProductResponse changeStatus(String productId, ProductStatusRequest request) {
        String shopId = currentUser.shopId();
        return apply(shopId, productId, request.version(), null, new Update().set("status", request.status()),
                () -> notFoundOrStale(shopId, productId));
    }

    // ==== Variants =======================================================================

    public ProductResponse addVariant(String productId, AddVariantRequest request) {
        String shopId = currentUser.shopId();
        Product product = load(shopId, productId);
        requireVersion(product, request.version());
        if (product.getVariants().size() >= ProductRules.MAX_VARIANTS) {
            throw ProductErrors.variantLimitReached();
        }
        String sku = ProductRules.normalizeSku(request.sku());
        if (hasSku(product, sku)) {
            throw SkuAlreadyExistsException.forField("sku", sku);
        }
        Variant variant = newVariant(request.toVariantRequest(), sku);
        List<Variant> all = new ArrayList<>(product.getVariants());
        all.add(variant);
        requireUniqueAttributes(all);
        requireSkusFree(shopId, Map.of(sku, "sku"), productId);

        Long[] range = ProductRules.priceRange(all);
        // Atomic: SKU not in this product yet, and fewer than 100 variants ("variants.99" missing).
        // A parallel request can therefore never push a duplicate SKU or a 101st variant.
        Criteria condition = new Criteria().andOperator(
                Criteria.where("variants.sku").ne(sku),
                Criteria.where("variants." + (ProductRules.MAX_VARIANTS - 1)).exists(false));
        Update update = new Update()
                .push("variants", variant)
                .set("minPrice", range[0])
                .set("maxPrice", range[1]);
        try {
            return apply(shopId, productId, request.version(), condition, update,
                    () -> addVariantFailure(shopId, productId, request.version(), sku));
        } catch (DuplicateKeyException e) {
            throw skuConflict(shopId, Map.of(sku, "sku"), productId);
        }
    }

    /**
     * Changes the given fields of one variant ({@code null} = unchanged). SKU and stock cannot be
     * changed. The name follows the attributes while it is still the default name.
     */
    public ProductResponse updateVariant(String productId, String variantId, UpdateVariantRequest request) {
        String shopId = currentUser.shopId();
        Product product = load(shopId, productId);
        Variant current = findVariant(product, variantId);
        requireVersion(product, request.version());

        Map<String, String> attributes = request.attributes() != null
                ? ProductRules.normalizeAttributes(request.attributes())
                : current.getAttributes();
        String name;
        if (request.name() != null) {
            String given = ProductRules.normalizeText(request.name());
            name = given != null ? given : ProductRules.defaultVariantName(attributes);
        } else if (request.attributes() != null
                && Objects.equals(current.getName(), ProductRules.defaultVariantName(current.getAttributes()))) {
            name = ProductRules.defaultVariantName(attributes);
        } else {
            name = current.getName();
        }

        Variant changed = new Variant();
        changed.setVariantId(current.getVariantId());
        changed.setSku(current.getSku());
        changed.setName(name);
        changed.setAttributes(attributes);
        changed.setPrice(request.price() != null ? request.price() : current.getPrice());
        changed.setCostPrice(request.costPrice() != null ? request.costPrice() : current.getCostPrice());
        changed.setLowStockThreshold(request.lowStockThreshold() != null
                ? request.lowStockThreshold() : current.getLowStockThreshold());
        changed.setActive(request.active() != null ? request.active() : current.isActive());

        List<Variant> all = product.getVariants().stream()
                .map(v -> v.getVariantId().equals(variantId) ? changed : v)
                .toList();
        requireUniqueAttributes(all);
        Long[] range = ProductRules.priceRange(all);

        // "variants.$" = the array element matched by the condition on variants.variantId.
        // Only these fields are written; variants.$.stock is never touched.
        Criteria condition = Criteria.where("variants.variantId").is(new ObjectId(variantId));
        Update update = new Update()
                .set("variants.$.name", changed.getName())
                .set("variants.$.attributes", changed.getAttributes())
                .set("variants.$.price", changed.getPrice())
                .set("variants.$.costPrice", changed.getCostPrice())
                .set("variants.$.lowStockThreshold", changed.getLowStockThreshold())
                .set("variants.$.active", changed.isActive())
                .set("minPrice", range[0])
                .set("maxPrice", range[1]);
        return apply(shopId, productId, request.version(), condition, update,
                () -> notFoundOrStale(shopId, productId));
    }

    // ==== Delete =========================================================================

    /**
     * Hard delete, only for mistakes: refused with {@code 409 PRODUCT_IN_USE} if any module
     * reports the product as used, or any variant has stock. Runs in a transaction so the product
     * delete and the image cleanup tasks are written together (or not at all).
     */
    @Transactional
    public void delete(String productId) {
        String shopId = currentUser.shopId();
        Product product = load(shopId, productId);
        boolean used = usageCheckers.stream().anyMatch(checker -> checker.isUsed(shopId, productId));
        if (used || hasStock(product)) {
            throw new ProductInUseException();
        }
        if (queryRepository.deleteIfUnstocked(shopId, productId) == 0) {
            // Gone meanwhile, or stock arrived after we loaded it.
            throw productRepository.existsByIdAndShopId(productId, shopId)
                    ? new ProductInUseException()
                    : ProductErrors.productNotFound();
        }
        List<String> publicIds = product.getImages().stream().map(ProductImage::getPublicId).toList();
        if (!publicIds.isEmpty()) {
            cleanupTasks.scheduleRemoval(shopId, publicIds);
            events.publishEvent(new ImagesRemovedEvent(shopId, publicIds));
        }
        publishChanged(shopId, productId);
    }

    // ==== Helpers ========================================================================

    Product load(String shopId, String productId) {
        return productRepository.findByIdAndShopId(productId, shopId)
                .orElseThrow(ProductErrors::productNotFound);
    }

    /**
     * Runs a targeted update with {@code version == expectedVersion} (+ {@code condition}),
     * stamps {@code updatedAt}, increments {@code version}, publishes {@link ProductChangedEvent}.
     */
    private ProductResponse apply(String shopId, String productId, Long expectedVersion, Criteria condition,
                                  Update update, Supplier<RuntimeException> onNoMatch) {
        update.set("updatedAt", Instant.now(clock)).inc("version", 1);
        Criteria versionMatches = Criteria.where("version").is(expectedVersion);
        Criteria full = condition == null ? versionMatches : new Criteria().andOperator(versionMatches, condition);
        Product updated = queryRepository.modify(shopId, productId, full, update).orElseThrow(onNoMatch);
        publishChanged(shopId, productId);
        return mapper.toResponse(updated);
    }

    private RuntimeException notFoundOrStale(String shopId, String productId) {
        return productRepository.existsByIdAndShopId(productId, shopId)
                ? ProductErrors.staleVersion()
                : ProductErrors.productNotFound();
    }

    /** The atomic add matched nothing: find out which of its conditions failed. */
    private RuntimeException addVariantFailure(String shopId, String productId, Long version, String sku) {
        Optional<Product> now = productRepository.findByIdAndShopId(productId, shopId);
        if (now.isEmpty()) {
            return ProductErrors.productNotFound();
        }
        Product product = now.get();
        if (!Objects.equals(product.getVersion(), version)) {
            return ProductErrors.staleVersion();
        }
        if (hasSku(product, sku)) {
            return SkuAlreadyExistsException.forField("sku", sku);
        }
        if (product.getVariants().size() >= ProductRules.MAX_VARIANTS) {
            return ProductErrors.variantLimitReached();
        }
        return ProductErrors.staleVersion();
    }

    private static void requireVersion(Product product, Long expected) {
        if (!Objects.equals(product.getVersion(), expected)) {
            throw ProductErrors.staleVersion();
        }
    }

    private static Variant findVariant(Product product, String variantId) {
        if (!ObjectId.isValid(variantId)) {
            throw ProductErrors.variantNotFound();
        }
        return product.getVariants().stream()
                .filter(v -> v.getVariantId().equals(variantId))
                .findFirst()
                .orElseThrow(ProductErrors::variantNotFound);
    }

    private static boolean hasSku(Product product, String sku) {
        return product.getVariants().stream().anyMatch(v -> v.getSku().equals(sku));
    }

    private static boolean hasStock(Product product) {
        return product.getVariants().stream()
                .map(Variant::getStock)
                .filter(Objects::nonNull)
                .anyMatch(s -> s.onHand() > 0 || s.reserved() > 0);
    }

    /** Two <b>active</b> variants must not have the same attributes (case and order ignored). */
    static void requireUniqueAttributes(List<Variant> variants) {
        Map<String, Variant> seen = new HashMap<>();
        for (Variant v : variants) {
            String signature = ProductRules.attributeSignature(v.getAttributes());
            if (v.isActive() && signature != null && seen.putIfAbsent(signature, v) != null) {
                throw ProductErrors.duplicateAttributes(v.getName());
            }
        }
    }

    /** Friendly 409 before writing; the unique index stays the real guarantee. */
    private void requireSkusFree(String shopId, Map<String, String> fieldBySku, String excludeProductId) {
        Set<String> taken = queryRepository.findSkusInUse(shopId, fieldBySku.keySet(), excludeProductId);
        if (!taken.isEmpty()) {
            throw skuError(fieldBySku, taken);
        }
    }

    /** After a {@link DuplicateKeyException}: name the clashing SKU(s) if we can find them. */
    private SkuAlreadyExistsException skuConflict(String shopId, Map<String, String> fieldBySku, String excludeProductId) {
        return skuError(fieldBySku, queryRepository.findSkusInUse(shopId, fieldBySku.keySet(), excludeProductId));
    }

    private static SkuAlreadyExistsException skuError(Map<String, String> fieldBySku, Set<String> taken) {
        List<ApiError.FieldError> errors = fieldBySku.entrySet().stream()
                .filter(e -> taken.contains(e.getKey()))
                .map(e -> new ApiError.FieldError(e.getValue(), "SKU " + e.getKey() + " already exists"))
                .toList();
        return new SkuAlreadyExistsException(errors);
    }

    private static Variant newVariant(VariantRequest request, String sku) {
        Map<String, String> attributes = ProductRules.normalizeAttributes(request.attributes());
        String name = ProductRules.normalizeText(request.name());
        Variant variant = new Variant();
        variant.setVariantId(new ObjectId().toHexString());
        variant.setSku(sku);
        variant.setName(name != null ? name : ProductRules.defaultVariantName(attributes));
        variant.setAttributes(attributes);
        variant.setPrice(request.price());
        variant.setCostPrice(request.costPrice());
        variant.setLowStockThreshold(request.lowStockThreshold());
        variant.setActive(true);
        // Opening stock is entered through Inventory.
        variant.setStock(VariantStock.empty());
        return variant;
    }

    private static void applyProductFields(Product product, String name, String description, String category) {
        String normalizedName = ProductRules.normalizeText(name);
        String normalizedCategory = ProductRules.normalizeText(category);
        product.setName(normalizedName);
        product.setNameLower(ProductRules.lower(normalizedName));
        product.setDescription(description == null || description.isBlank() ? null : description.trim());
        product.setCategory(normalizedCategory);
        product.setCategoryKey(ProductRules.lower(normalizedCategory));
    }

    private static void setOrUnset(Update update, String field, Object value) {
        if (value == null) {
            update.unset(field);
        } else {
            update.set(field, value);
        }
    }

    private static ProductStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank()) {
            return ProductStatus.ACTIVE;
        }
        if ("ALL".equals(status)) {
            return null;
        }
        try {
            return ProductStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException("status", "must be ACTIVE, ARCHIVED or ALL");
        }
    }

    private void publishChanged(String shopId, String productId) {
        events.publishEvent(new ProductChangedEvent(shopId, productId));
    }
}
