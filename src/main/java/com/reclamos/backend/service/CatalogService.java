package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.CategoryResponse;
import com.reclamos.backend.dto.response.RequestTypeResponse;
import com.reclamos.backend.dto.response.SubcategoryResponse;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.CategoryRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.SubcategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService {
    private final CategoryRepository categoryRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final RequestTypeRepository requestTypeRepository;

    public List<CategoryResponse> getCategories() {
        return categoryRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    public List<SubcategoryResponse> getSubcategories(Long categoryId) {
        categoryRepository.findById(categoryId)
                .filter(Category::isActive)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "La categoría solicitada no existe o está inactiva"));

        return subcategoryRepository.findByCategory_IdAndActiveTrueOrderByNameAsc(categoryId).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<RequestTypeResponse> getRequestTypes(Long subcategoryId) {
        subcategoryRepository.findById(subcategoryId)
                .filter(Subcategory::isActive)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "La subcategoría solicitada no existe o está inactiva"));

        return requestTypeRepository.findBySubcategory_IdAndActiveTrueOrderByNameAsc(subcategoryId).stream()
                .map(this::toResponse)
                .toList();
    }

    private CategoryResponse toResponse(Category category) {
        CategoryResponse response = new CategoryResponse();
        response.setId(category.getId());
        response.setName(category.getName());
        response.setDescription(category.getDescription());
        return response;
    }

    private SubcategoryResponse toResponse(Subcategory subcategory) {
        SubcategoryResponse response = new SubcategoryResponse();
        response.setId(subcategory.getId());
        response.setName(subcategory.getName());
        response.setDescription(subcategory.getDescription());
        return response;
    }

    private RequestTypeResponse toResponse(RequestType requestType) {
        RequestTypeResponse response = new RequestTypeResponse();
        response.setId(requestType.getId());
        response.setCode(requestType.getCode());
        response.setName(requestType.getName());
        response.setDescription(requestType.getDescription());
        response.setTicketType(requestType.getTicketType());
        response.setResponsibleAreaId(requestType.getResponsibleAreaId());
        response.setAllowsAnonymous(requestType.isAllowsAnonymous());
        response.setRequiresLocation(requestType.isRequiresLocation());
        return response;
    }
}