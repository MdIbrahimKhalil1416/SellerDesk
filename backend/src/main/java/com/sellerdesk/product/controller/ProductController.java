package com.sellerdesk.product.controller;

import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.product.dto.AddVariantRequest;
import com.sellerdesk.product.dto.CategoryResponse;
import com.sellerdesk.product.dto.CreateProductRequest;
import com.sellerdesk.product.dto.ProductLookupItem;
import com.sellerdesk.product.dto.ProductResponse;
import com.sellerdesk.product.dto.ProductStatusRequest;
import com.sellerdesk.product.dto.ProductSummary;
import com.sellerdesk.product.dto.UpdateProductRequest;
import com.sellerdesk.product.dto.UpdateVariantRequest;
import com.sellerdesk.product.repository.ProductSort;
import com.sellerdesk.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Products and variants (docs/API.md §4, docs/modules/PRODUCT.md §5). Images:
 * {@link ProductImageController}.
 *
 * <p>Thin: check the permission ({@code @PreAuthorize}), validate ({@code @Valid}), call the
 * service. The shop is never a parameter: it comes from the JWT inside the service.
 * Money values are in poisha (৳1 = 100).
 */
@Tag(name = "Products", description = "Catalog: products with variants (SKU, price). Stock is managed by Inventory.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    static final String VIEW = "hasAuthority('PRODUCT_VIEW')";
    static final String EDIT = "hasAuthority('PRODUCT_EDIT')";

    private final ProductService productService;

    @Operation(summary = "List products",
            description = "Search, filter, sort and page. `q` matches an exact SKU or the start of the name. "
                    + "`totalAvailable` only with STOCK_VIEW.")
    @ApiResponse(responseCode = "200", description = "A page of products")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (unknown sort or status, page/size out of range)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing PRODUCT_VIEW",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    @PreAuthorize(VIEW)
    public PageResponse<ProductSummary> list(
            @Parameter(description = "Exact SKU or name prefix") @RequestParam(required = false) @Size(max = 200) String q,
            @Parameter(description = "Category name (case-insensitive)") @RequestParam(required = false) @Size(max = 50) String category,
            @Parameter(description = "ACTIVE (default), ARCHIVED or ALL")
            @RequestParam(defaultValue = "ACTIVE") @Pattern(regexp = "ACTIVE|ARCHIVED|ALL") String status,
            @Parameter(description = "updatedAt,desc (default), name,asc, name,desc, createdAt,desc, minPrice,asc, minPrice,desc")
            @RequestParam(defaultValue = "updatedAt,desc") @Pattern(regexp = ProductSort.PARAM_REGEX) String sort,
            @Parameter(description = "0-based page") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size, max 100") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return productService.list(q, category, status, sort, page, size);
    }

    @Operation(summary = "Product picker for the order form",
            description = "Active products with their active variants. No stock and no cost price. Cached per shop.")
    @ApiResponse(responseCode = "200", description = "Products for the order form")
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: needs PRODUCT_VIEW or ORDER_CREATE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/lookup")
    @PreAuthorize("hasAnyAuthority('PRODUCT_VIEW', 'ORDER_CREATE')")
    public List<ProductLookupItem> lookup() {
        return productService.lookup();
    }

    @Operation(summary = "Categories in use", description = "For autocomplete and the category filter.")
    @ApiResponse(responseCode = "200", description = "Categories with product counts")
    @GetMapping("/categories")
    @PreAuthorize(VIEW)
    public List<CategoryResponse> categories() {
        return productService.categories();
    }

    @Operation(summary = "Get a product",
            description = "`costPrice` only with PRODUCT_EDIT or REPORT_VIEW, `stock` only with STOCK_VIEW.")
    @ApiResponse(responseCode = "200", description = "The product")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (unknown, or of another shop)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public ProductResponse get(@PathVariable String id) {
        return productService.get(id);
    }

    @Operation(summary = "Create a product with its variants",
            description = "Stock starts at 0; opening stock is entered through Inventory. SKUs are uppercased.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (also: same SKU twice in the request)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "SKU_ALREADY_EXISTS or DUPLICATE_VARIANT_ATTRIBUTES",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        return productService.create(request);
    }

    @Operation(summary = "Update product fields", description = "Name, description, category. Variants and images have own endpoints.")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION (stale version)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{id}")
    @PreAuthorize(EDIT)
    public ProductResponse update(@PathVariable String id, @Valid @RequestBody UpdateProductRequest request) {
        return productService.update(id, request);
    }

    @Operation(summary = "Archive or unarchive", description = "Archived products cannot be added to new orders.")
    @ApiResponse(responseCode = "200", description = "Status changed")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PatchMapping("/{id}/status")
    @PreAuthorize(EDIT)
    public ProductResponse changeStatus(@PathVariable String id, @Valid @RequestBody ProductStatusRequest request) {
        return productService.changeStatus(id, request);
    }

    @Operation(summary = "Add a variant")
    @ApiResponse(responseCode = "201", description = "Variant added; the whole product is returned")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "SKU_ALREADY_EXISTS, DUPLICATE_VARIANT_ATTRIBUTES, VARIANT_LIMIT_REACHED or CONCURRENT_MODIFICATION",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public ProductResponse addVariant(@PathVariable String id, @Valid @RequestBody AddVariantRequest request) {
        return productService.addVariant(id, request);
    }

    @Operation(summary = "Edit a variant",
            description = "Only the fields sent are changed. SKU and stock cannot be changed; `active=false` deactivates.")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (product or variant)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "DUPLICATE_VARIANT_ATTRIBUTES or CONCURRENT_MODIFICATION",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PatchMapping("/{id}/variants/{variantId}")
    @PreAuthorize(EDIT)
    public ProductResponse updateVariant(@PathVariable String id, @PathVariable String variantId,
                                         @Valid @RequestBody UpdateVariantRequest request) {
        return productService.updateVariant(id, variantId, request);
    }

    @Operation(summary = "Delete a product",
            description = "Only for products never used by an order or stock movement and without stock. Otherwise archive it.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "PRODUCT_IN_USE", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(EDIT)
    public void delete(@PathVariable String id) {
        productService.delete(id);
    }
}
