package com.sellerdesk.customer.controller;

import com.sellerdesk.common.config.OpenApiConfig;
import com.sellerdesk.common.dto.PageResponse;
import com.sellerdesk.common.error.ApiError;
import com.sellerdesk.customer.dto.AddressRequest;
import com.sellerdesk.customer.dto.CreateCustomerRequest;
import com.sellerdesk.customer.dto.CustomerResponse;
import com.sellerdesk.customer.dto.CustomerStatusRequest;
import com.sellerdesk.customer.dto.CustomerSummary;
import com.sellerdesk.customer.dto.TagCount;
import com.sellerdesk.customer.dto.UpdateCustomerRequest;
import com.sellerdesk.customer.service.CustomerService;
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
 * Customers (docs/API.md §6, docs/modules/CUSTOMER.md §3). Thin: permission, validation, service.
 * The shop always comes from the JWT inside the service. No delete: customers are archived.
 */
@Tag(name = "Customers", description = "Customer records: phone (unique per shop), addresses, tags, order counters")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
public class CustomerController {

    static final String VIEW = "hasAuthority('CUSTOMER_VIEW')";
    static final String EDIT = "hasAuthority('CUSTOMER_EDIT')";

    private final CustomerService customerService;

    @Operation(summary = "List customers", description = "`q`: digits search the phone (start or last 4+ digits, also the "
            + "alternative phone); text searches the start of the name.")
    @ApiResponse(responseCode = "200", description = "A page of customers")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "FORBIDDEN: missing CUSTOMER_VIEW", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    @PreAuthorize(VIEW)
    public PageResponse<CustomerSummary> list(
            @Parameter(description = "Name prefix or phone digits") @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 30) String tag,
            @Parameter(description = "ACTIVE (default), ARCHIVED or ALL")
            @RequestParam(defaultValue = "ACTIVE") @Pattern(regexp = "ACTIVE|ARCHIVED|ALL") String status,
            @Parameter(description = "createdAt,desc (default), name,asc or lastOrderAt,desc")
            @RequestParam(defaultValue = "createdAt,desc") @Pattern(regexp = "createdAt,desc|name,asc|lastOrderAt,desc") String sort,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return customerService.list(q, tag, status, sort, page, size);
    }

    @Operation(summary = "Tags in use", description = "Tags of active customers with counts, for autocomplete and the filter.")
    @ApiResponse(responseCode = "200", description = "Tags")
    @GetMapping("/tags")
    @PreAuthorize(VIEW)
    public List<TagCount> tags() {
        return customerService.tags();
    }

    @Operation(summary = "Find a customer by phone", description = "Exact match on the main phone, in any accepted format. "
            + "For the order form: also allowed with ORDER_CREATE. Archived customers are found too (see status).")
    @ApiResponse(responseCode = "200", description = "The customer")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED: not a Bangladeshi mobile number",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "NOT_FOUND: no customer with this phone", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/lookup")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_VIEW', 'ORDER_CREATE')")
    public CustomerResponse lookup(@RequestParam @Size(max = 30) String phone) {
        return customerService.lookup(phone);
    }

    @Operation(summary = "Get a customer")
    @ApiResponse(responseCode = "200", description = "The customer")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (unknown, or of another shop)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public CustomerResponse get(@PathVariable String id) {
        return customerService.get(id);
    }

    @Operation(summary = "Create a customer", description = "Phone is stored as 01XXXXXXXXX. The first address is the default "
            + "unless another one has isDefault=true.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "PHONE_ALREADY_EXISTS (also for an archived customer's phone)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public CustomerResponse create(@Valid @RequestBody CreateCustomerRequest request) {
        return customerService.create(request);
    }

    @Operation(summary = "Update the profile", description = "Name, phones, Facebook link, note, tags. Optional fields left out are cleared.")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "PHONE_ALREADY_EXISTS or CONCURRENT_MODIFICATION",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{id}")
    @PreAuthorize(EDIT)
    public CustomerResponse update(@PathVariable String id, @Valid @RequestBody UpdateCustomerRequest request) {
        return customerService.update(id, request);
    }

    @Operation(summary = "Archive or restore", description = "ARCHIVED hides the customer from the default list. There is no delete.")
    @ApiResponse(responseCode = "200", description = "Status changed")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "CONCURRENT_MODIFICATION", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PatchMapping("/{id}/status")
    @PreAuthorize(EDIT)
    public CustomerResponse changeStatus(@PathVariable String id, @Valid @RequestBody CustomerStatusRequest request) {
        return customerService.changeStatus(id, request);
    }

    @Operation(summary = "Add an address", description = "Max 5. isDefault=true makes it the default.")
    @ApiResponse(responseCode = "201", description = "Added; the whole customer is returned")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "ADDRESS_LIMIT_REACHED", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    public CustomerResponse addAddress(@PathVariable String id, @Valid @RequestBody AddressRequest request) {
        return customerService.addAddress(id, request);
    }

    @Operation(summary = "Replace an address", description = "isDefault=true moves the default mark here; false keeps the current default.")
    @ApiResponse(responseCode = "200", description = "Updated")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (customer or address)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PutMapping("/{id}/addresses/{addressId}")
    @PreAuthorize(EDIT)
    public CustomerResponse updateAddress(@PathVariable String id, @PathVariable String addressId,
                                          @Valid @RequestBody AddressRequest request) {
        return customerService.updateAddress(id, addressId, request);
    }

    @Operation(summary = "Remove an address", description = "If it was the default, the oldest remaining address becomes default.")
    @ApiResponse(responseCode = "200", description = "Removed")
    @ApiResponse(responseCode = "404", description = "NOT_FOUND (customer or address)", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @DeleteMapping("/{id}/addresses/{addressId}")
    @PreAuthorize(EDIT)
    public CustomerResponse removeAddress(@PathVariable String id, @PathVariable String addressId) {
        return customerService.removeAddress(id, addressId);
    }
}
