package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.CategoryResponse;
import com.reclamos.backend.dto.response.RequestTypeResponse;
import com.reclamos.backend.dto.response.SubcategoryResponse;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.CategoryRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.SubcategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private SubcategoryRepository subcategoryRepository;
    @Mock
    private RequestTypeRepository requestTypeRepository;

    private CatalogService service;

    @BeforeEach
    void setUp() {
        service = new CatalogService(categoryRepository, subcategoryRepository, requestTypeRepository);
    }

    @Test
    void returnsOnlyActiveCategoriesAsDtosInRepositoryOrder() {
        when(categoryRepository.findByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(category(2L, "Alumbrado", true), category(1L, "Calles", true)));

        List<CategoryResponse> result = service.getCategories();

        assertEquals(List.of("Alumbrado", "Calles"), result.stream().map(CategoryResponse::getName).toList());
        assertInstanceOf(CategoryResponse.class, result.getFirst());
        verify(categoryRepository).findByActiveTrueOrderByNameAsc();
    }

    @Test
    void returnsOnlyActiveSubcategoriesFromRequestedCategoryInRepositoryOrder() {
        Category category = category(10L, "Infraestructura", true);
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category));
        when(subcategoryRepository.findByCategory_IdAndActiveTrueOrderByNameAsc(10L))
                .thenReturn(List.of(subcategory(3L, category, "Calles", true),
                        subcategory(4L, category, "Veredas", true)));

        List<SubcategoryResponse> result = service.getSubcategories(10L);

        assertEquals(List.of("Calles", "Veredas"), result.stream().map(SubcategoryResponse::getName).toList());
        assertInstanceOf(SubcategoryResponse.class, result.getFirst());
        verify(subcategoryRepository).findByCategory_IdAndActiveTrueOrderByNameAsc(10L);
    }

    @Test
    void missingCategoryCannotBeNavigated() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getSubcategories(99L));
        verify(subcategoryRepository, never()).findByCategory_IdAndActiveTrueOrderByNameAsc(99L);
    }

    @Test
    void inactiveCategoryCannotBeNavigated() {
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category(10L, "Inactiva", false)));

        assertThrows(ResourceNotFoundException.class, () -> service.getSubcategories(10L));
        verify(subcategoryRepository, never()).findByCategory_IdAndActiveTrueOrderByNameAsc(10L);
    }

    @Test
    void returnsOnlyActiveRequestTypesFromRequestedSubcategoryInRepositoryOrder() {
        Category category = category(10L, "Infraestructura", true);
        Subcategory subcategory = subcategory(20L, category, "Calles", true);
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(subcategory));
        when(requestTypeRepository.findBySubcategory_IdAndActiveTrueOrderByNameAsc(20L))
                .thenReturn(List.of(requestType(8L, subcategory, "BACHE", "Informar bache"),
                        requestType(9L, subcategory, "PAVIMENTO", "Reparar pavimento")));

        List<RequestTypeResponse> result = service.getRequestTypes(20L);

        assertEquals(List.of("Informar bache", "Reparar pavimento"),
                result.stream().map(RequestTypeResponse::getName).toList());
        assertEquals(TicketType.COMPLAINT, result.getFirst().getTicketType());
        assertEquals("Obras Públicas", result.getFirst().getResponsibleAreaId());
        assertInstanceOf(RequestTypeResponse.class, result.getFirst());
        verify(requestTypeRepository).findBySubcategory_IdAndActiveTrueOrderByNameAsc(20L);
    }

    @Test
    void missingSubcategoryCannotBeNavigated() {
        when(subcategoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getRequestTypes(99L));
        verify(requestTypeRepository, never()).findBySubcategory_IdAndActiveTrueOrderByNameAsc(99L);
    }

    @Test
    void inactiveSubcategoryCannotBeNavigated() {
        Subcategory subcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", false);
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(subcategory));

        assertThrows(ResourceNotFoundException.class, () -> service.getRequestTypes(20L));
        verify(requestTypeRepository, never()).findBySubcategory_IdAndActiveTrueOrderByNameAsc(20L);
    }

    private Category category(Long id, String name, boolean active) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setDescription(name + " descripción");
        category.setActive(active);
        return category;
    }

    private Subcategory subcategory(Long id, Category category, String name, boolean active) {
        Subcategory subcategory = new Subcategory();
        subcategory.setId(id);
        subcategory.setCategory(category);
        subcategory.setName(name);
        subcategory.setActive(active);
        return subcategory;
    }

    private RequestType requestType(Long id, Subcategory subcategory, String code, String name) {
        RequestType requestType = new RequestType();
        requestType.setId(id);
        requestType.setSubcategory(subcategory);
        requestType.setCode(code);
        requestType.setName(name);
        requestType.setDescription(name + " descripción");
        requestType.setTicketType(TicketType.COMPLAINT);
        requestType.setResponsibleAreaId("Obras Públicas");
        requestType.setAllowsAnonymous(true);
        requestType.setRequiresLocation(true);
        requestType.setActive(true);
        return requestType;
    }
}