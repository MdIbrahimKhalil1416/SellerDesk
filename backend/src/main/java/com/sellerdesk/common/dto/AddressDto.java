package com.sellerdesk.common.dto;

import com.sellerdesk.common.model.Address;
import com.sellerdesk.common.model.DeliveryZone;
import com.sellerdesk.common.util.Normalize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Address in requests and responses. Same fields as {@link Address}, plus validation. */
@Schema(description = "Postal address")
public record AddressDto(
        @Schema(example = "House 12, Road 5, Dhanmondi") @NotBlank @Size(max = 200) String line,
        @Schema(example = "Dhanmondi") @Size(max = 100) String area,
        @Schema(example = "Dhaka") @NotBlank @Size(max = 50) String district,
        @NotNull DeliveryZone zone,
        @Schema(example = "Opposite the mosque") @Size(max = 200) String note) {

    public Address toModel() {
        return new Address(line.trim(), Normalize.trimToNull(area), district.trim(), zone, Normalize.trimToNull(note));
    }

    public static AddressDto from(Address address) {
        return address == null ? null
                : new AddressDto(address.line(), address.area(), address.district(), address.zone(), address.note());
    }
}
