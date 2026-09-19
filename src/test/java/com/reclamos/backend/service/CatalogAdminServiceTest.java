package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CategoryAdminRequest;
import com.reclamos.backend.dto.request.RequestTypeAdminRequest;
import com.reclamos.backend.dto.request.SubcategoryAdminRequest;
import com.reclamos.backend.dto.response.CategoryAdminResponse;
import com.reclamos.backend.dto.response.RequestTypeAdminResponse;
import com.reclamos.backend.dto.response.SubcategoryAdminResponse;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.exception.InvalidCatalogRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.repository.CategoryRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.SubcategoryRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE - DDA2-114/115/116 (CRUD admin) y DDA2-120/121/122 (activar/desactivar)
 * de {@link CatalogAdminService}. La cascada de integridad jerárquica
 * (DDA2-126/127/128/129) es historia separada y todavía no está
 * implementada: {@link #deactivateCategorySetsActiveFalseWithoutTouchingChildren()}
 * documenta explícitamente que hoy NO cascadea.
 */
@ExtendWith(MockitoExtension.class)
class CatalogAdminServiceTest {
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private SubcategoryRepository subcategoryRepository;
    @Mock
    private RequestTypeRepository requestTypeRepository;
    @Mock
    private TicketRepository ticketRepository;

    private CatalogAdminService service;

    @BeforeEach
    void setUp() {
        lenient().when(categoryRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(invocation -> categoryRepository.findById(invocation.getArgument(0)));
        lenient().when(subcategoryRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(invocation -> subcategoryRepository.findById(invocation.getArgument(0)));
        lenient().when(subcategoryRepository.findCategoryIdById(anyLong()))
                .thenAnswer(invocation -> subcategoryRepository.findById(invocation.getArgument(0))
                        .map(subcategory -> subcategory.getCategory().getId()));
        lenient().when(requestTypeRepository.findByIdForUpdate(anyLong()))
                .thenAnswer(invocation -> requestTypeRepository.findById(invocation.getArgument(0)));
        lenient().when(requestTypeRepository.findSubcategoryIdById(anyLong()))
                .thenAnswer(invocation -> requestTypeRepository.findById(invocation.getArgument(0))
                        .map(requestType -> requestType.getSubcategory().getId()));
        lenient().when(subcategoryRepository.findByCategoryIdOrderByIdAscForUpdate(anyLong()))
                .thenAnswer(invocation -> subcategoryRepository.findByCategory_IdOrderByNameAsc(
                        invocation.getArgument(0)));
        lenient().when(requestTypeRepository.findBySubcategoryIdOrderByIdAscForUpdate(anyLong()))
                .thenAnswer(invocation -> requestTypeRepository.findBySubcategory_IdOrderByNameAsc(
                        invocation.getArgument(0)));
        service = new CatalogAdminService(
                categoryRepository, subcategoryRepository, requestTypeRepository, ticketRepository);
    }

    // ---- Category: listado y CRUD ----

    @Test
    void listCategoriesReturnsAllCategoriesRegardlessOfActiveState() {
        when(categoryRepository.findAllByOrderByNameAsc())
                .thenReturn(List.of(category(1L, "Alumbrado", true), category(2L, "Calles", false)));

        List<CategoryAdminResponse> result = service.listCategories();

        assertEquals(List.of("Alumbrado", "Calles"), result.stream().map(CategoryAdminResponse::getName).toList());
        assertEquals(List.of(true, false), result.stream().map(CategoryAdminResponse::isActive).toList());
    }

    @Test
    void createCategorySavesNewCategoryWhenNameIsUnique() {
        when(categoryRepository.existsByNameIgnoreCase("Alumbrado")).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryAdminResponse response =
                service.createCategory(categoryRequest("Alumbrado", "Reclamos de alumbrado"));

        assertEquals("Alumbrado", response.getName());
        assertEquals("Reclamos de alumbrado", response.getDescription());
        ArgumentCaptor<Category> captor = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(captor.capture());
        assertTrue(captor.getValue().isActive());
    }

    @Test
    void createCategoryRejectsDuplicateNameCaseInsensitive() {
        when(categoryRepository.existsByNameIgnoreCase("alumbrado")).thenReturn(true);

        assertThrows(InvalidCatalogRequestException.class,
                () -> service.createCategory(categoryRequest("alumbrado", "desc")));
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void updateCategoryUpdatesNameAndDescriptionWithoutTouchingActive() {
        Category existing = category(1L, "Alumbrado", true);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("Alumbrado público", 1L)).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryAdminResponse response = service.updateCategory(1L,
                categoryRequest("Alumbrado público", "Nueva descripción"));

        assertEquals("Alumbrado público", response.getName());
        assertEquals("Nueva descripción", response.getDescription());
        assertTrue(response.isActive());
    }

    @Test
    void updateCategoryRejectsDuplicateNameFromAnotherCategory() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category(1L, "Alumbrado", true)));
        when(categoryRepository.existsByNameIgnoreCaseAndIdNot("Calles", 1L)).thenReturn(true);

        assertThrows(InvalidCatalogRequestException.class,
                () -> service.updateCategory(1L, categoryRequest("Calles", "desc")));
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void updateCategoryMissingCategoryThrowsNotFound() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updateCategory(99L, categoryRequest("X", "Y")));
    }

    // ---- Category: activar/desactivar (DDA2-120/121/122) ----

    @Test
    void deactivateCategoryCascadesDeactivationToSubcategoriesAndRequestTypes() {
        // DDA2-126/127: al desactivar la Category, sus Subcategories (activa
        // o no) y los Request Types de cada una quedan todos en active=false.
        Category existing = category(1L, "Alumbrado", true);
        Subcategory postes = subcategory(10L, existing, "Postes", true);
        Subcategory cableado = subcategory(11L, existing, "Cableado", false);
        RequestType posteCaido = requestType(100L, postes, "POSTE_CAIDO", "Poste caído", true);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(subcategoryRepository.findByCategory_IdOrderByNameAsc(1L))
                .thenReturn(List.of(postes, cableado));
        when(subcategoryRepository.save(any(Subcategory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(requestTypeRepository.findBySubcategory_IdOrderByNameAsc(10L)).thenReturn(List.of(posteCaido));
        when(requestTypeRepository.findBySubcategory_IdOrderByNameAsc(11L)).thenReturn(List.of());
        when(requestTypeRepository.save(any(RequestType.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CategoryAdminResponse response = service.deactivateCategory(1L);

        assertFalse(response.isActive());
        assertFalse(postes.isActive());
        assertFalse(cableado.isActive());
        assertFalse(posteCaido.isActive());
        verify(subcategoryRepository).save(postes);
        verify(subcategoryRepository).save(cableado);
        verify(requestTypeRepository).save(posteCaido);
    }

    @Test
    void activateCategorySetsActiveTrueWithoutValidatingAnyParent() {
        Category existing = category(1L, "Alumbrado", false);
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.save(any(Category.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryAdminResponse response = service.activateCategory(1L);

        assertTrue(response.isActive());
    }

    @Test
    void deactivateCategoryMissingCategoryThrowsNotFound() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.deactivateCategory(99L));
    }

    // ---- Subcategory: listado y CRUD ----

    @Test
    void listSubcategoriesReturnsAllUnderCategoryRegardlessOfActiveState() {
        Category category = category(10L, "Infraestructura", true);
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category));
        when(subcategoryRepository.findByCategory_IdOrderByNameAsc(10L))
                .thenReturn(List.of(subcategory(1L, category, "Calles", true),
                        subcategory(2L, category, "Veredas", false)));

        List<SubcategoryAdminResponse> result = service.listSubcategories(10L);

        assertEquals(List.of("Calles", "Veredas"), result.stream().map(SubcategoryAdminResponse::getName).toList());
        assertEquals(List.of(true, false), result.stream().map(SubcategoryAdminResponse::isActive).toList());
    }

    @Test
    void listSubcategoriesMissingCategoryThrowsNotFound() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.listSubcategories(99L));
    }

    @Test
    void createSubcategoryRejectsInactiveParentCategory() {
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category(10L, "Infraestructura", false)));

        assertThrows(InvalidCatalogRequestException.class,
                () -> service.createSubcategory(subcategoryRequest(10L, "Calles", "desc")));
        verify(subcategoryRepository, never()).save(any());
    }

    @Test
    void createSubcategoryRejectsDuplicateNameInSameCategory() {
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category(10L, "Infraestructura", true)));
        when(subcategoryRepository.existsByCategory_IdAndNameIgnoreCase(10L, "Calles")).thenReturn(true);

        assertThrows(InvalidCatalogRequestException.class,
                () -> service.createSubcategory(subcategoryRequest(10L, "Calles", "desc")));
        verify(subcategoryRepository, never()).save(any());
    }

    @Test
    void createSubcategorySavesWhenParentActiveAndNameIsUnique() {
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category(10L, "Infraestructura", true)));
        when(subcategoryRepository.existsByCategory_IdAndNameIgnoreCase(10L, "Calles")).thenReturn(false);
        when(subcategoryRepository.save(any(Subcategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SubcategoryAdminResponse response = service.createSubcategory(subcategoryRequest(10L, "Calles", "desc"));

        assertEquals("Calles", response.getName());
        assertEquals(10L, response.getCategoryId());
        assertTrue(response.isActive());
    }

    @Test
    void updateSubcategoryDoesNotRevalidateUnchangedParentEvenIfItBecameInactive() {
        // La Category ya está inactiva, pero como NO se está reasignando el
        // padre (categoryId sigue siendo el mismo), la edición de
        // nombre/descripción no debe bloquearse por eso.
        Category inactiveCategory = category(10L, "Infraestructura", false);
        Subcategory existing = subcategory(1L, inactiveCategory, "Calles", true);
        when(subcategoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(inactiveCategory));
        when(subcategoryRepository.existsByCategory_IdAndNameIgnoreCaseAndIdNot(10L, "Calles pavimentadas", 1L))
                .thenReturn(false);
        when(subcategoryRepository.save(any(Subcategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SubcategoryAdminResponse response = service.updateSubcategory(1L,
                subcategoryRequest(10L, "Calles pavimentadas", "desc"));

        assertEquals("Calles pavimentadas", response.getName());
        verify(categoryRepository).findByIdForUpdate(10L);
    }

    @Test
    void updateSubcategoryRejectsReassigningToInactiveCategory() {
        Category currentCategory = category(10L, "Infraestructura", true);
        Subcategory existing = subcategory(1L, currentCategory, "Calles", true);
        when(subcategoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(currentCategory));
        when(categoryRepository.findById(20L)).thenReturn(Optional.of(category(20L, "Espacios verdes", false)));

        assertThrows(InvalidCatalogRequestException.class,
                () -> service.updateSubcategory(1L, subcategoryRequest(20L, "Calles", "desc")));
        verify(subcategoryRepository, never()).save(any());
    }

    // ---- Subcategory: activar/desactivar (DDA2-120/121/122) ----

    @Test
    void deactivateSubcategoryCascadesToRequestTypesButNotToCategory() {
        // DDA2-126/127: cascadea a sus Request Types, pero la Category
        // (el padre) no se toca — sólo Category -> Subcategory/RequestType
        // cascadea, nunca hacia arriba.
        Category category = category(10L, "Infraestructura", true);
        Subcategory existing = subcategory(1L, category, "Calles", true);
        RequestType bache = requestType(100L, existing, "BACHE", "Informar bache", true);
        when(subcategoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category));
        when(subcategoryRepository.save(any(Subcategory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(requestTypeRepository.findBySubcategory_IdOrderByNameAsc(1L)).thenReturn(List.of(bache));
        when(requestTypeRepository.save(any(RequestType.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SubcategoryAdminResponse response = service.deactivateSubcategory(1L);

        assertFalse(response.isActive());
        assertFalse(bache.isActive());
        assertTrue(category.isActive());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void activateSubcategoryRejectsWhenCategoryIsInactive() {
        Subcategory existing = subcategory(1L, category(10L, "Infraestructura", false), "Calles", false);
        when(subcategoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(existing.getCategory()));

        assertThrows(InvalidCatalogRequestException.class, () -> service.activateSubcategory(1L));
        verify(subcategoryRepository, never()).save(any());
    }

    @Test
    void activateSubcategorySetsActiveTrueWhenCategoryIsActive() {
        Subcategory existing = subcategory(1L, category(10L, "Infraestructura", true), "Calles", false);
        when(subcategoryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(existing.getCategory()));
        when(subcategoryRepository.save(any(Subcategory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SubcategoryAdminResponse response = service.activateSubcategory(1L);

        assertTrue(response.isActive());
    }

    // ---- RequestType: listado y CRUD ----

    @Test
    void listRequestTypesReturnsAllUnderSubcategoryRegardlessOfActiveState() {
        Subcategory subcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", true);
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(subcategory));
        when(requestTypeRepository.findBySubcategory_IdOrderByNameAsc(20L))
                .thenReturn(List.of(requestType(1L, subcategory, "BACHE", "Informar bache", true),
                        requestType(2L, subcategory, "LUMINARIA", "Informar luminaria", false)));

        List<RequestTypeAdminResponse> result = service.listRequestTypes(20L);

        assertEquals(List.of("Informar bache", "Informar luminaria"),
                result.stream().map(RequestTypeAdminResponse::getName).toList());
        assertEquals(List.of(true, false), result.stream().map(RequestTypeAdminResponse::isActive).toList());
    }

    @Test
    void createRequestTypeRejectsInactiveParentSubcategory() {
        Subcategory inactiveSubcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", false);
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(inactiveSubcategory));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(inactiveSubcategory.getCategory()));

        assertThrows(InvalidCatalogRequestException.class,
                () -> service.createRequestType(requestTypeRequest(20L, "BACHE", "Informar bache")));
        verify(requestTypeRepository, never()).save(any());
    }

    @Test
    void createRequestTypeRejectsWhenSubcategoryActiveButItsCategoryIsInactive() {
        // DDA2-128/129: cadena transitiva también al crear, no sólo al
        // activar — misma razón que activateRequestTypeRejectsWhen...
        // más abajo.
        Category inactiveCategory = category(10L, "Infraestructura", false);
        Subcategory subcategoryWithInactiveCategory = subcategory(20L, inactiveCategory, "Calles", true);
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(subcategoryWithInactiveCategory));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(inactiveCategory));

        assertThrows(InvalidCatalogRequestException.class,
                () -> service.createRequestType(requestTypeRequest(20L, "BACHE", "Informar bache")));
        verify(requestTypeRepository, never()).save(any());
    }

    @Test
    void createRequestTypeRejectsDuplicateCode() {
        Subcategory subcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", true);
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(subcategory));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(subcategory.getCategory()));
        when(requestTypeRepository.existsByCodeIgnoreCase("BACHE")).thenReturn(true);

        assertThrows(InvalidCatalogRequestException.class,
                () -> service.createRequestType(requestTypeRequest(20L, "BACHE", "Informar bache")));
        verify(requestTypeRepository, never()).save(any());
    }

    @Test
    void createRequestTypeRejectsDuplicateNameInSameSubcategory() {
        Subcategory subcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", true);
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(subcategory));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(subcategory.getCategory()));
        when(requestTypeRepository.existsByCodeIgnoreCase("BACHE")).thenReturn(false);
        when(requestTypeRepository.existsBySubcategory_IdAndNameIgnoreCase(20L, "Informar bache")).thenReturn(true);

        assertThrows(InvalidCatalogRequestException.class,
                () -> service.createRequestType(requestTypeRequest(20L, "BACHE", "Informar bache")));
        verify(requestTypeRepository, never()).save(any());
    }

    @Test
    void createRequestTypeSavesWhenParentActiveAndCodeAndNameAreUnique() {
        Subcategory subcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", true);
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(subcategory));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(subcategory.getCategory()));
        when(requestTypeRepository.existsByCodeIgnoreCase("BACHE")).thenReturn(false);
        when(requestTypeRepository.existsBySubcategory_IdAndNameIgnoreCase(20L, "Informar bache")).thenReturn(false);
        when(requestTypeRepository.save(any(RequestType.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RequestTypeAdminResponse response =
                service.createRequestType(requestTypeRequest(20L, "BACHE", "Informar bache"));

        assertEquals("BACHE", response.getCode());
        assertEquals(20L, response.getSubcategoryId());
        assertTrue(response.isActive());
    }

    @Test
    void updateRequestTypeRejectsReassigningToInactiveSubcategory() {
        Subcategory currentSubcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", true);
        Subcategory newInactiveSubcategory =
                subcategory(30L, category(10L, "Infraestructura", true), "Veredas", false);
        RequestType existing = requestType(1L, currentSubcategory, "BACHE", "Informar bache", true);
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(currentSubcategory.getCategory()));
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(currentSubcategory));
        when(subcategoryRepository.findById(30L)).thenReturn(Optional.of(newInactiveSubcategory));

        assertThrows(InvalidCatalogRequestException.class,
                () -> service.updateRequestType(1L, requestTypeRequest(30L, "BACHE", "Informar bache")));
        verify(requestTypeRepository, never()).save(any());
    }

    @Test
    void updateRequestTypeAllowsChangingBaseRiskWhileUnused() {
        Subcategory subcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", true);
        RequestType existing = requestType(1L, subcategory, "BACHE", "Informar bache", true);
        stubRequestTypeHierarchy(existing);
        when(ticketRepository.existsByRequestType_Id(1L)).thenReturn(false);
        when(requestTypeRepository.save(any(RequestType.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RequestTypeAdminRequest request = requestTypeRequest(20L, "BACHE", "Informar bache");
        request.setBaseRisk(Risk.HIGH);

        RequestTypeAdminResponse response = service.updateRequestType(1L, request);

        assertEquals(Risk.HIGH, response.getBaseRisk());
        assertEquals(Risk.HIGH, existing.getBaseRisk());
    }

    @Test
    void updateRequestTypeAllowsKeepingBaseRiskAfterUse() {
        Subcategory subcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", true);
        RequestType existing = requestType(1L, subcategory, "BACHE", "Informar bache", true);
        stubRequestTypeHierarchy(existing);
        when(ticketRepository.existsByRequestType_Id(1L)).thenReturn(true);
        when(requestTypeRepository.save(any(RequestType.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RequestTypeAdminRequest request = requestTypeRequest(20L, "BACHE", "Informar bache editado");
        request.setBaseRisk(Risk.LOW);

        RequestTypeAdminResponse response = service.updateRequestType(1L, request);

        assertEquals(Risk.LOW, response.getBaseRisk());
        assertEquals("Informar bache editado", response.getName());
    }

    @Test
    void updateRequestTypeRejectsChangingBaseRiskAfterUseBeforeMutatingAnyField() {
        Subcategory subcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", true);
        RequestType existing = requestType(1L, subcategory, "BACHE", "Informar bache", true);
        stubRequestTypeHierarchy(existing);
        when(ticketRepository.existsByRequestType_Id(1L)).thenReturn(true);
        RequestTypeAdminRequest request = requestTypeRequest(20L, "BACHE_EDITADO", "Nombre editado");
        request.setBaseRisk(Risk.CRITICAL);

        assertThrows(InvalidCatalogRequestException.class, () -> service.updateRequestType(1L, request));

        assertEquals(Risk.LOW, existing.getBaseRisk());
        assertEquals("BACHE", existing.getCode());
        assertEquals("Informar bache", existing.getName());
        verify(requestTypeRepository, never()).save(any());
    }

    // ---- RequestType: activar/desactivar (DDA2-120/121/122) ----

    @Test
    void deactivateRequestTypeSetsActiveFalse() {
        Subcategory subcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", true);
        RequestType existing = requestType(1L, subcategory, "BACHE", "Informar bache", true);
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(subcategory.getCategory()));
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(subcategory));
        when(requestTypeRepository.save(any(RequestType.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RequestTypeAdminResponse response = service.deactivateRequestType(1L);

        assertFalse(response.isActive());
    }

    @Test
    void activateRequestTypeRejectsWhenSubcategoryIsInactive() {
        Subcategory inactiveSubcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", false);
        RequestType existing = requestType(1L, inactiveSubcategory, "BACHE", "Informar bache", false);
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(inactiveSubcategory.getCategory()));
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(inactiveSubcategory));

        assertThrows(InvalidCatalogRequestException.class, () -> service.activateRequestType(1L));
        verify(requestTypeRepository, never()).save(any());
    }

    @Test
    void activateRequestTypeRejectsWhenCategoryIsInactiveEvenIfSubcategoryIsActive() {
        // Cadena transitiva: la Subcategory (padre inmediato) está activa,
        // pero su Category (abuelo) no — igual debe rechazarse. Es el mismo
        // invariante que DDA2-126/127/128/129 formaliza para toda la
        // jerarquía ("No puede existir un Request Type activo cuya
        // Subcategory O Category esté inactiva").
        Category inactiveCategory = category(10L, "Infraestructura", false);
        Subcategory subcategoryWithInactiveCategory = subcategory(20L, inactiveCategory, "Calles", true);
        RequestType existing = requestType(1L, subcategoryWithInactiveCategory, "BACHE", "Informar bache", false);
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(inactiveCategory));
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(subcategoryWithInactiveCategory));

        assertThrows(InvalidCatalogRequestException.class, () -> service.activateRequestType(1L));
        verify(requestTypeRepository, never()).save(any());
    }

    @Test
    void activateRequestTypeSetsActiveTrueWhenSubcategoryAndCategoryAreActive() {
        Subcategory subcategory = subcategory(20L, category(10L, "Infraestructura", true), "Calles", true);
        RequestType existing = requestType(1L, subcategory, "BACHE", "Informar bache", false);
        when(requestTypeRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(subcategory.getCategory()));
        when(subcategoryRepository.findById(20L)).thenReturn(Optional.of(subcategory));
        when(requestTypeRepository.save(any(RequestType.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RequestTypeAdminResponse response = service.activateRequestType(1L);

        assertTrue(response.isActive());
    }

    // ---- helpers ----

    private CategoryAdminRequest categoryRequest(String name, String description) {
        CategoryAdminRequest request = new CategoryAdminRequest();
        request.setName(name);
        request.setDescription(description);
        return request;
    }

    private SubcategoryAdminRequest subcategoryRequest(Long categoryId, String name, String description) {
        SubcategoryAdminRequest request = new SubcategoryAdminRequest();
        request.setCategoryId(categoryId);
        request.setName(name);
        request.setDescription(description);
        return request;
    }

    private RequestTypeAdminRequest requestTypeRequest(Long subcategoryId, String code, String name) {
        RequestTypeAdminRequest request = new RequestTypeAdminRequest();
        request.setSubcategoryId(subcategoryId);
        request.setCode(code);
        request.setName(name);
        request.setDescription(name + " descripción");
        request.setTicketType(TicketType.COMPLAINT);
        request.setResponsibleAreaId("M3");
        request.setMinimumPriority(Priority.LOW);
        request.setBaseRisk(Risk.LOW);
        request.setAffectedPopulationFactor(BigDecimal.valueOf(0.1));
        request.setAllowsAnonymous(true);
        request.setRequiresLocation(true);
        return request;
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
        subcategory.setDescription(name + " descripción");
        subcategory.setActive(active);
        return subcategory;
    }

    private RequestType requestType(Long id, Subcategory subcategory, String code, String name, boolean active) {
        RequestType requestType = new RequestType();
        requestType.setId(id);
        requestType.setSubcategory(subcategory);
        requestType.setCode(code);
        requestType.setName(name);
        requestType.setDescription(name + " descripción");
        requestType.setTicketType(TicketType.COMPLAINT);
        requestType.setResponsibleAreaId("M3");
        requestType.setMinimumPriority(Priority.LOW);
        requestType.setBaseRisk(Risk.LOW);
        requestType.setAffectedPopulationFactor(BigDecimal.valueOf(0.1));
        requestType.setAllowsAnonymous(true);
        requestType.setRequiresLocation(true);
        requestType.setActive(active);
        return requestType;
    }

    private void stubRequestTypeHierarchy(RequestType requestType) {
        Subcategory subcategory = requestType.getSubcategory();
        when(requestTypeRepository.findById(requestType.getId())).thenReturn(Optional.of(requestType));
        when(subcategoryRepository.findById(subcategory.getId())).thenReturn(Optional.of(subcategory));
        when(categoryRepository.findById(subcategory.getCategory().getId()))
                .thenReturn(Optional.of(subcategory.getCategory()));
    }
}
