package com.sellerdesk.order.controller;

import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.order.dto.BulkStatusRequest;
import com.sellerdesk.order.dto.BulkStatusResponse;
import com.sellerdesk.order.dto.CancelRequest;
import com.sellerdesk.order.dto.CreateOrderRequest;
import com.sellerdesk.order.dto.OrderResponse;
import com.sellerdesk.order.dto.OrderSummary;
import com.sellerdesk.order.dto.TimelineEntry;
import com.sellerdesk.order.dto.ReturnRequest;
import com.sellerdesk.order.dto.ShipRequest;
import com.sellerdesk.order.dto.StatusNoteRequest;
import com.sellerdesk.order.dto.UpdateOrderRequest;
import com.sellerdesk.order.service.OrderService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orders (docs/API.md §7, docs/modules/ORDER.md §8). Thin: permission, validation, service. The
 * shop always comes from the JWT inside the service.
 *
 * <p>One endpoint per status change (not {@code PATCH {status}}): each has its own permission,
 * body and side effects, so each is easy to secure and test.
 */
@Tag(name = "Orders", description = "Order pipeline: create, edit while PENDING, confirm → pack → ship → deliver / return, cancel")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "Create an order", description = "Status PENDING. The server computes all totals and the order "
            + "number. Stock is not reserved yet (that happens on confirm). The same requestId again returns the first "
            + "order with 200.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "200", description = "Repeated requestId: the order created the first time")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED / MALFORMED_REQUEST", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: customer or variant unknown or of another shop", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "VARIANT_NOT_ORDERABLE or REQUEST_ID_REUSED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderService.CreateResult result = orderService.create(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
    }

    @Operation(summary = "List orders", description = "Newest first by default. `status` may be repeated "
            + "(`?status=PENDING&status=CONFIRMED`). `from`/`to`: Dhaka days, both inclusive. `q`: order number "
            + "(SD-1042, 1042) or customer phone in any format.")
    @ApiResponse(responseCode = "200", description = "A page of orders")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (unknown status/source/sort, bad date, from after to, bad q)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public PageResponse<OrderSummary> list(
            @Parameter(description = "PENDING, CONFIRMED, PACKED, SHIPPED, DELIVERED, CANCELLED, RETURNED; repeatable")
            @RequestParam(required = false) @Size(max = 7) List<@Size(max = 20) String> status,
            @Parameter(description = "FACEBOOK, MESSENGER, WHATSAPP, PHONE, OTHER")
            @RequestParam(required = false) @Size(max = 20) String source,
            @Parameter(description = "YYYY-MM-DD, Dhaka day, inclusive") @RequestParam(required = false) @Size(max = 10) String from,
            @Parameter(description = "YYYY-MM-DD, Dhaka day, inclusive") @RequestParam(required = false) @Size(max = 10) String to,
            @Parameter(description = "Order number or customer phone") @RequestParam(required = false) @Size(max = 30) String q,
            @Parameter(description = "createdAt,desc (default) or createdAt,asc")
            @RequestParam(defaultValue = "createdAt,desc") @Pattern(regexp = "createdAt,desc|createdAt,asc") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orderService.list(status, source, from, to, q, sort, page, size);
    }

    @Operation(summary = "Change the status of many orders", description = "Same action on 1-50 orders; each runs on its "
            + "own and the result lists every order (ok, or errorCode). Needs the permission of the action. RETURN is not "
            + "possible in bulk; CANCEL needs a reason.")
    @ApiResponse(responseCode = "200", description = "Per-order results (some may have failed)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing the permission of the action",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/bulk-status")
    @PreAuthorize("hasAnyAuthority('ORDER_CONFIRM', 'ORDER_FULFILL', 'ORDER_CLOSE', 'ORDER_CANCEL')")
    public BulkStatusResponse bulkStatus(@Valid @RequestBody BulkStatusRequest request) {
        return orderService.bulk(request);
    }

    @Operation(summary = "Order timeline", description = "Status changes and edits, oldest first.")
    @ApiResponse(responseCode = "200", description = "The timeline")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (unknown, or of another shop)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public List<TimelineEntry> timeline(@PathVariable String id) {
        return orderService.timeline(id);
    }

    @Operation(summary = "Get an order")
    @ApiResponse(responseCode = "200", description = "The order")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (unknown, or of another shop)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public OrderResponse get(@PathVariable String id) {
        return orderService.get(id);
    }

    @Operation(summary = "Edit a PENDING order", description = "Full replace of customer name, delivery address, items, "
            + "money inputs, source and note. Items already in the order keep their stored price unless a new one is sent.")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "ORDER_NOT_EDITABLE, VARIANT_NOT_ORDERABLE or CONCURRENT_MODIFICATION",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ORDER_EDIT')")
    public OrderResponse update(@PathVariable String id, @Valid @RequestBody UpdateOrderRequest request) {
        return orderService.update(id, request);
    }

    @Operation(summary = "Confirm (PENDING → CONFIRMED)", description = "Reserves stock for every item in one transaction: "
            + "all items or none.")
    @ApiResponse(responseCode = "200", description = "Confirmed (or already confirmed)")
    @ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK (fieldErrors per short item) or INVALID_STATUS_TRANSITION",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('ORDER_CONFIRM')")
    public OrderResponse confirm(@PathVariable String id, @Valid @RequestBody(required = false) StatusNoteRequest request) {
        return orderService.confirm(id, request == null ? null : request.note());
    }

    @Operation(summary = "Pack (CONFIRMED → PACKED)")
    @ApiResponse(responseCode = "200", description = "Packed")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/pack")
    @PreAuthorize("hasAuthority('ORDER_FULFILL')")
    public OrderResponse pack(@PathVariable String id, @Valid @RequestBody(required = false) StatusNoteRequest request) {
        return orderService.pack(id, request == null ? null : request.note());
    }

    @Operation(summary = "Ship (PACKED → SHIPPED)", description = "The reserved units leave the shelf (stock DEDUCT).")
    @ApiResponse(responseCode = "200", description = "Shipped")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/ship")
    @PreAuthorize("hasAuthority('ORDER_FULFILL')")
    public OrderResponse ship(@PathVariable String id, @Valid @RequestBody(required = false) ShipRequest request) {
        return orderService.ship(id, request);
    }

    @Operation(summary = "Deliver (SHIPPED → DELIVERED)", description = "Counts the order on the customer (delivered, total spent).")
    @ApiResponse(responseCode = "200", description = "Delivered")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/deliver")
    @PreAuthorize("hasAuthority('ORDER_CLOSE')")
    public OrderResponse deliver(@PathVariable String id, @Valid @RequestBody(required = false) StatusNoteRequest request) {
        return orderService.deliver(id, request == null ? null : request.note());
    }

    @Operation(summary = "Return (SHIPPED → RETURNED)", description = "Items not listed go back on the shelf; restock=false "
            + "for damaged items.")
    @ApiResponse(responseCode = "200", description = "Returned")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/return")
    @PreAuthorize("hasAuthority('ORDER_CLOSE')")
    public OrderResponse returnOrder(@PathVariable String id, @Valid @RequestBody ReturnRequest request) {
        return orderService.returnOrder(id, request);
    }

    @Operation(summary = "Cancel", description = "From PENDING, CONFIRMED or PACKED. Reserved stock is released.")
    @ApiResponse(responseCode = "200", description = "Cancelled")
    @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION (e.g. SHIPPED: use return)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ORDER_CANCEL')")
    public OrderResponse cancel(@PathVariable String id, @Valid @RequestBody CancelRequest request) {
        return orderService.cancel(id, request.reason());
    }
}
