package com.sellerdesk.courier.controller;

import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.courier.dto.BulkShipmentRequest;
import com.sellerdesk.courier.dto.BulkShipmentResponse;
import com.sellerdesk.courier.dto.CancelShipmentRequest;
import com.sellerdesk.courier.dto.SendShipmentRequest;
import com.sellerdesk.courier.dto.ShipmentResponse;
import com.sellerdesk.courier.dto.ShipmentSummary;
import com.sellerdesk.courier.service.ShipmentService;
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
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Courier shipments (docs/API.md §8, docs/modules/COURIER.md §4). Thin: permission, validation,
 * service. The shop always comes from the JWT inside the service.
 */
@Tag(name = "Shipments", description = "Send PACKED orders to a courier, track and cancel bookings")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ShipmentController {

    private final ShipmentService shipmentService;

    @Operation(summary = "Send an order to a courier", description = "Only PACKED orders. The order stays PACKED; it "
            + "becomes SHIPPED when the courier reports the pickup. The same courier again returns the active shipment.")
    @ApiResponse(responseCode = "201", description = "Booked (CREATED)")
    @ApiResponse(responseCode = "202", description = "BOOKING: the courier did not answer; SellerDesk retries automatically")
    @ApiResponse(responseCode = "200", description = "Already booked with this courier")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED / COURIER_NOT_AVAILABLE", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (unknown, or of another shop)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "ORDER_NOT_PACKED, SHIPMENT_ALREADY_ACTIVE or COURIER_REJECTED",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/orders/{id}/shipment")
    @PreAuthorize("hasAuthority('ORDER_FULFILL')")
    public ResponseEntity<ShipmentResponse> send(@PathVariable String id, @Valid @RequestBody SendShipmentRequest request) {
        ShipmentService.SendResult result = shipmentService.send(id, request);
        return ResponseEntity.status(result.status()).body(result.shipment());
    }

    @Operation(summary = "Shipments of an order", description = "Every booking attempt, newest first.")
    @ApiResponse(responseCode = "200", description = "The shipments")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/orders/{id}/shipments")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public List<ShipmentSummary> forOrder(@PathVariable String id) {
        return shipmentService.forOrder(id);
    }

    @Operation(summary = "List shipments", description = "Newest change first. E.g. `?status=RETURNED` for the "
            + "\"Returned by courier\" tab, `?status=FAILED` for failed bookings.")
    @ApiResponse(responseCode = "200", description = "A page of shipments")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (unknown status / provider)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/shipments")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public PageResponse<ShipmentSummary> list(
            @Parameter(description = "BOOKING, CREATED, PICKED_UP, IN_TRANSIT, DELIVERED, RETURNED, CANCELLED, FAILED")
            @RequestParam(required = false) @Size(max = 20) String status,
            @Parameter(description = "MOCK") @RequestParam(required = false) @Size(max = 20) String provider,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return shipmentService.list(status, provider, page, size);
    }

    @Operation(summary = "Send many orders to a courier", description = "1-20 PACKED orders, one after the other; the "
            + "result lists every order (ok, or errorCode).")
    @ApiResponse(responseCode = "200", description = "Per-order results (some may have failed)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED / COURIER_NOT_AVAILABLE", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/shipments/bulk")
    @PreAuthorize("hasAuthority('ORDER_FULFILL')")
    public BulkShipmentResponse bulk(@Valid @RequestBody BulkShipmentRequest request) {
        return shipmentService.bulk(request);
    }

    @Operation(summary = "Get a shipment")
    @ApiResponse(responseCode = "200", description = "The shipment")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (unknown, or of another shop)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/shipments/{id}")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ShipmentResponse get(@PathVariable String id) {
        return shipmentService.get(id);
    }

    @Operation(summary = "Track a shipment", description = "Asks the courier for the current status (at most once a "
            + "minute) and applies it like a webhook, which may move the order.")
    @ApiResponse(responseCode = "200", description = "The shipment, updated")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "COURIER_UNAVAILABLE: nothing changed", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/shipments/{id}/track")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ShipmentResponse track(@PathVariable String id) {
        return shipmentService.track(id);
    }

    @Operation(summary = "Cancel a shipment", description = "Only before pickup (BOOKING, CREATED). The order stays "
            + "PACKED and can be sent again.")
    @ApiResponse(responseCode = "200", description = "Cancelled")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "SHIPMENT_NOT_CANCELLABLE", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "COURIER_UNAVAILABLE: nothing changed", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/shipments/{id}/cancel")
    @PreAuthorize("hasAuthority('ORDER_FULFILL')")
    public ShipmentResponse cancel(@PathVariable String id, @Valid @RequestBody CancelShipmentRequest request) {
        return shipmentService.cancel(id, request.reason());
    }
}
