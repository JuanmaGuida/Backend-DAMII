package com.reclamos.backend.controller;

import com.reclamos.backend.dto.request.CategoryAdminRequest;
import com.reclamos.backend.dto.request.RequestTypeAdminRequest;
import com.reclamos.backend.dto.request.SubcategoryAdminRequest;
import com.reclamos.backend.dto.response.CategoryAdminResponse;
import com.reclamos.backend.dto.response.RequestTypeAdminResponse;
import com.reclamos.backend.dto.response.SubcategoryAdminResponse;
import com.reclamos.backend.service.CatalogAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * BE - DDA2-114/115/116 (US "Panel de administración del catálogo"): CRUD
 * administrativo de Categories/Subcategories/Request Types. Restringido a
 * ADMIN en SecurityConfiguration; la verificación exhaustiva de esa
 * autorización (tests, casos de acceso denegado) es DDA2-139/140/141,
 * historia separada.
 */
@RestController
@RequestMapping("/api/admin/catalog")
@RequiredArgsConstructor
public class CatalogAdminController {
    private final CatalogAdminService catalogAdminService;

    @GetMapping("/categories")
    public List<CategoryAdminResponse> getCategories() {
        return catalogAdminService.listCategories();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryAdminResponse createCategory(@Valid @RequestBody CategoryAdminRequest request) {
        return catalogAdminService.createCategory(request);
    }

    @PutMapping("/categories/{categoryId}")
    public CategoryAdminResponse updateCategory(
            @PathVariable Long categoryId, @Valid @RequestBody CategoryAdminRequest request) {
        return catalogAdminService.updateCategory(categoryId, request);
    }

    @GetMapping("/categories/{categoryId}/subcategories")
    public List<SubcategoryAdminResponse> getSubcategories(@PathVariable Long categoryId) {
        return catalogAdminService.listSubcategories(categoryId);
    }

    @PostMapping("/categories/{categoryId}/deactivate")
    public CategoryAdminResponse deactivateCategory(@PathVariable Long categoryId) {
        return catalogAdminService.deactivateCategory(categoryId);
    }

    @PostMapping("/categories/{categoryId}/activate")
    public CategoryAdminResponse activateCategory(@PathVariable Long categoryId) {
        return catalogAdminService.activateCategory(categoryId);
    }

    @PostMapping("/subcategories")
    @ResponseStatus(HttpStatus.CREATED)
    public SubcategoryAdminResponse createSubcategory(@Valid @RequestBody SubcategoryAdminRequest request) {
        return catalogAdminService.createSubcategory(request);
    }

    @PutMapping("/subcategories/{subcategoryId}")
    public SubcategoryAdminResponse updateSubcategory(
            @PathVariable Long subcategoryId, @Valid @RequestBody SubcategoryAdminRequest request) {
        return catalogAdminService.updateSubcategory(subcategoryId, request);
    }

    @PostMapping("/subcategories/{subcategoryId}/deactivate")
    public SubcategoryAdminResponse deactivateSubcategory(@PathVariable Long subcategoryId) {
        return catalogAdminService.deactivateSubcategory(subcategoryId);
    }

    @PostMapping("/subcategories/{subcategoryId}/activate")
    public SubcategoryAdminResponse activateSubcategory(@PathVariable Long subcategoryId) {
        return catalogAdminService.activateSubcategory(subcategoryId);
    }

    @GetMapping("/subcategories/{subcategoryId}/request-types")
    public List<RequestTypeAdminResponse> getRequestTypes(@PathVariable Long subcategoryId) {
        return catalogAdminService.listRequestTypes(subcategoryId);
    }

    @PostMapping("/request-types")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestTypeAdminResponse createRequestType(@Valid @RequestBody RequestTypeAdminRequest request) {
        return catalogAdminService.createRequestType(request);
    }

    @PutMapping("/request-types/{requestTypeId}")
    public RequestTypeAdminResponse updateRequestType(
            @PathVariable Long requestTypeId, @Valid @RequestBody RequestTypeAdminRequest request) {
        return catalogAdminService.updateRequestType(requestTypeId, request);
    }

    @PostMapping("/request-types/{requestTypeId}/deactivate")
    public RequestTypeAdminResponse deactivateRequestType(@PathVariable Long requestTypeId) {
        return catalogAdminService.deactivateRequestType(requestTypeId);
    }

    @PostMapping("/request-types/{requestTypeId}/activate")
    public RequestTypeAdminResponse activateRequestType(@PathVariable Long requestTypeId) {
        return catalogAdminService.activateRequestType(requestTypeId);
    }
}
