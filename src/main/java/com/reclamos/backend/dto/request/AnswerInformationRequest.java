package com.reclamos.backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.Valid;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerInformationRequest {
    private String responseMessage;
    private Map<String, Object> formData;
    @Valid
    private CreateTicketRequest.LocationData location;

    public AnswerInformationRequest(String responseMessage) {
        this.responseMessage = responseMessage;
    }
}
