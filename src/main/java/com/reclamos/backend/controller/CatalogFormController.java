package com.reclamos.backend.controller;

import com.reclamos.backend.dto.response.FormDefinitionResponse;
import com.reclamos.backend.service.FormService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalog/request-types")
@RequiredArgsConstructor
public class CatalogFormController {
    private final FormService formService;

    @GetMapping("/{requestTypeId}/form")
    public FormDefinitionResponse getForm(@PathVariable Long requestTypeId) {
        return formService.getFormForRequestType(requestTypeId);
    }
}

