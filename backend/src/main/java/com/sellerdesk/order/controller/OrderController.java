package com.sellerdesk.order.controller;

import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.order.dto.CancelRequest;
import com.sellerdesk.order.dto.CreateOrderRequest;
import com.sellerdesk.order.dto.OrderResponse;
import com.sellerdesk.order.dto.ReturnRequest;
import com.sellerdesk.order.dto.ShipRequest;
import com.sellerdesk.order.dto.StatusNoteRequest;
import com.sellerdesk.order.dto.UpdateOrderRequest;
import com.sellerdesk.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
