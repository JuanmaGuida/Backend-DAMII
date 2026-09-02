package com.reclamos.backend.controller;

import com.reclamos.backend.dto.response.CategoryResponse;
import com.reclamos.backend.dto.response.RequestTypeResponse;
import com.reclamos.backend.dto.response.SubcategoryResponse;
import com.reclamos.backend.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
public class CatalogController {
    private final CatalogService catalogService;

    @GetMapping("/categories")
    public List<CategoryResponse> getCategories() {
        return catalogService.getCategories();
    }

    @GetMapping("/categories/{categoryId}/subcategories")
    public List<SubcategoryResponse> getSubcategories(@PathVariable Long categoryId) {
        return catalogService.getSubcategories(categoryId);
    }

    @GetMapping("/subcategories/{subcategoryId}/request-types")
    public List<RequestTypeResponse> getRequestTypes(@PathVariable Long subcategoryId) {
        return catalogService.getRequestTypes(subcategoryId);
    }
}