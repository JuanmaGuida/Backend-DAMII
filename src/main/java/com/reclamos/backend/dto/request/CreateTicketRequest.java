package com.reclamos.backend.dto.request;

import com.reclamos.backend.entity.AnonymousContactChannel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record CreateTicketRequest(
        @NotNull Long requestTypeId,
        @NotBlank @Size(max = 200) String summary,
        @NotBlank String description,
        @NotNull Map<String, Object> formData,
        @Valid LocationData location,
        String anonymousAccessPassword,
        @Valid AnonymousContact anonymousContact
) {
    public CreateTicketRequest(Long requestTypeId, String summary, String description,
                               Map<String, Object> formData, LocationData location) {
        this(requestTypeId, summary, description, formData, location, null, null);
    }

    public boolean containsAnonymousData() {
        return anonymousAccessPassword != null || anonymousContact != null;
    }

    @Override
    public String toString() {
        return "CreateTicketRequest[requestTypeId=" + requestTypeId
                + ", summary=" + summary
                + ", description=" + description
                + ", formData=" + formData
                + ", location=" + location
                + ", anonymousAccessPassword=<redacted>"
                + ", anonymousContact=<redacted>]";
    }

    public record AnonymousContact(
            AnonymousContactChannel channel,
            String value
    ) {
        @Override
        public String toString() {
            return "AnonymousContact[channel=" + channel + ", value=<redacted>]";
        }
    }

    public record LocationData(
            @Size(max = 300) String addressLine,
            @Size(max = 150) String street,
            @Size(max = 30) String streetNumber,
            UUID neighborhoodId,
            BigDecimal latitude,
            BigDecimal longitude,
            @Size(max = 500) String reference
    ) { }
}
