package com.reclamos.backend.controller;

import com.reclamos.backend.dto.request.FormTemplateAdminRequest;
import com.reclamos.backend.dto.response.FormTemplateAdminResponse;
import com.reclamos.backend.service.FormAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BE - DDA2-133/134/135 (US "Administración del schema de formularios
 * dinámicos"). Vive bajo /api/admin/catalog, así que ya queda cubierto por
 * el gate hasRole("ADMIN") de SecurityConfiguration sin tocar nada ahí.
 */
@RestController
@RequestMapping("/api/admin/catalog/request-types/{requestTypeId}/form")
@RequiredArgsConstructor
public class FormAdminController {
    private final FormAdminService formAdminService;

    @GetMapping
    public FormTemplateAdminResponse getForm(@PathVariable Long requestTypeId) {
        return formAdminService.getForm(requestTypeId);
    }

    @PutMapping
    public FormTemplateAdminResponse saveForm(
            @PathVariable Long requestTypeId, @Valid @RequestBody FormTemplateAdminRequest request) {
        return formAdminService.saveForm(requestTypeId, request);
    }
}
