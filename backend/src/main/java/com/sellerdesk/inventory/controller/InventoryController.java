package com.sellerdesk.inventory.controller;

import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.inventory.dto.AdjustmentRequest;
import com.sellerdesk.inventory.dto.MovementResponse;
import com.sellerdesk.inventory.dto.RestockRequest;
import com.sellerdesk.inventory.dto.StockChangeResponse;
import com.sellerdesk.inventory.dto.StockRow;
import com.sellerdesk.inventory.dto.VariantStockDetail;
import com.sellerdesk.inventory.service.InventoryService;
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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock levels and the movement log (docs/API.md §5, docs/modules/INVENTORY.md §5).
 *
 * <p>Thin: permission ({@code @PreAuthorize}), validation ({@code @Valid}), service call. The shop
 * always comes from the JWT inside the service; there is no shopId parameter anywhere.
 * Reserve / release / deduct / return have no endpoint: only order status changes cause them.
 */
@Tag(name = "Inventory", description = "Stock per variant (onHand, reserved, available) and the stock movement log")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    static final String VIEW = "hasAuthority('STOCK_VIEW')";
    static final String ADJUST = "hasAuthority('STOCK_ADJUST')";

    private final InventoryService inventoryService;

    @Operation(summary = "Stock list", description = "One row per variant. Default: active variants of active products. "
            + "The threshold is the variant's own lowStockThreshold, else the shop's defaultLowStockThreshold.")
    @ApiResponse(responseCode = "200", description = "A page of stock rows")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing STOCK_VIEW", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    @PreAuthorize(VIEW)
    public PageResponse<StockRow> list(
            @Parameter(description = "Exact SKU or product name prefix") @RequestParam(required = false) @Size(max = 200) String q,
            @Parameter(description = "ALL (default), LOW, OUT or LOW_OR_OUT")
            @RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|LOW|OUT|LOW_OR_OUT") String stockStatus,
            @Parameter(description = "true: also inactive variants and archived products")
            @RequestParam(defaultValue = "false") @Pattern(regexp = "true|false") String includeInactive,
            @Parameter(description = "name,asc (default) or available,asc")
            @RequestParam(defaultValue = "name,asc") @Pattern(regexp = "name,asc|available,asc") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return inventoryService.list(q, stockStatus, Boolean.parseBoolean(includeInactive), sort, page, size);
    }

    @Operation(summary = "Low-stock list", description = "LOW or OUT, active variants of active products, lowest available first.")
    @ApiResponse(responseCode = "200", description = "A page of stock rows")
    @GetMapping("/low-stock")
    @PreAuthorize(VIEW)
    public PageResponse<StockRow> lowStock(@RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return inventoryService.lowStock(page, size);
    }

    @Operation(summary = "Stock of one variant", description = "With its 10 newest movements.")
    @ApiResponse(responseCode = "200", description = "The variant's stock")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (unknown, or of another shop)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/variants/{variantId}")
    @PreAuthorize(VIEW)
    public VariantStockDetail variant(@PathVariable String variantId) {
        return inventoryService.variant(variantId);
    }

    @Operation(summary = "Add stock", description = "The first stock-in of a variant is recorded as INITIAL, later ones as RESTOCK. "
            + "Send a new requestId (UUID) per submit; a repeat returns the first result without adding again.")
    @ApiResponse(responseCode = "201", description = "Stock added")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing STOCK_ADJUST", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "STOCK_LIMIT_EXCEEDED or REQUEST_ID_REUSED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/variants/{variantId}/restock")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADJUST)
    public StockChangeResponse restock(@PathVariable String variantId, @Valid @RequestBody RestockRequest request) {
        return inventoryService.restock(variantId, request);
    }

    @Operation(summary = "Correct stock by hand", description = "Signed quantityChange with a reasonCode (note required for OTHER). "
            + "A decrease can only remove unreserved units.")
    @ApiResponse(responseCode = "201", description = "Stock corrected")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing STOCK_ADJUST", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK, STOCK_LIMIT_EXCEEDED or REQUEST_ID_REUSED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/variants/{variantId}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADJUST)
    public StockChangeResponse adjust(@PathVariable String variantId, @Valid @RequestBody AdjustmentRequest request) {
        return inventoryService.adjust(variantId, request);
    }

    @Operation(summary = "Movement history", description = "Newest first. Filters are optional; from is inclusive, to exclusive.")
    @ApiResponse(responseCode = "200", description = "A page of movements")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/movements")
    @PreAuthorize(VIEW)
    public PageResponse<MovementResponse> movements(
            @RequestParam(required = false) String variantId,
            @RequestParam(required = false) String productId,
            @Parameter(description = "INITIAL, RESTOCK, ADJUSTMENT, RESERVE, RELEASE, DEDUCT or RETURN")
            @RequestParam(required = false) @Pattern(regexp = "INITIAL|RESTOCK|ADJUSTMENT|RESERVE|RELEASE|DEDUCT|RETURN") String type,
            @Parameter(description = "DAMAGED, LOST, FOUND, COUNT_CORRECTION or OTHER")
            @RequestParam(required = false) @Pattern(regexp = "DAMAGED|LOST|FOUND|COUNT_CORRECTION|OTHER") String reasonCode,
            @RequestParam(required = false) String orderId,
            @Parameter(description = "2026-11-01 or 2026-11-01T10:00:00Z (inclusive)") @RequestParam(required = false) String from,
            @Parameter(description = "Same formats (exclusive)") @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return inventoryService.movements(variantId, productId, type, reasonCode, orderId, from, to, page, size);
    }
}
