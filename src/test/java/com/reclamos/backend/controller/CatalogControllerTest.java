package com.reclamos.backend.controller;

import com.reclamos.backend.dto.response.CategoryResponse;
import com.reclamos.backend.dto.response.FormDefinitionResponse;
import com.reclamos.backend.dto.response.RequestTypeResponse;
import com.reclamos.backend.dto.response.SubcategoryResponse;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.exception.GlobalExceptionHandler;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.service.CatalogService;
import com.reclamos.backend.service.FormService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CatalogControllerTest {
    @Mock
    private CatalogService catalogService;
    @Mock
    private FormService formService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new CatalogController(catalogService), new CatalogFormController(formService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listsCategories() throws Exception {
        CategoryResponse category = new CategoryResponse();
        category.setId(1L);
        category.setName("Infraestructura");
        category.setDescription("Espacio urbano");
        when(catalogService.getCategories()).thenReturn(List.of(category));

        mockMvc.perform(get("/api/catalog/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Infraestructura"))
                .andExpect(jsonPath("$[0].active").doesNotExist());
    }

    @Test
    void listsSubcategories() throws Exception {
        SubcategoryResponse subcategory = new SubcategoryResponse();
        subcategory.setId(2L);
        subcategory.setName("Calles");
        when(catalogService.getSubcategories(1L)).thenReturn(List.of(subcategory));

        mockMvc.perform(get("/api/catalog/categories/1/subcategories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].name").value("Calles"));
    }

    @Test
    void listsRequestTypes() throws Exception {
        RequestTypeResponse requestType = new RequestTypeResponse();
        requestType.setId(3L);
        requestType.setCode("INFORMAR_UN_BACHE");
        requestType.setName("Informar un bache");
        requestType.setTicketType(TicketType.COMPLAINT);
        requestType.setResponsibleAreaId("Obras Públicas");
        when(catalogService.getRequestTypes(2L)).thenReturn(List.of(requestType));

        mockMvc.perform(get("/api/catalog/subcategories/2/request-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("INFORMAR_UN_BACHE"))
                .andExpect(jsonPath("$[0].ticketType").value("COMPLAINT"))
                .andExpect(jsonPath("$[0].responsibleAreaId").value("Obras Públicas"))
                .andExpect(jsonPath("$[0].minimumPriority").doesNotExist());
    }

    @Test
    void missingOrInactiveCatalogParentReturnsNotFound() throws Exception {
        when(catalogService.getSubcategories(99L))
                .thenThrow(new ResourceNotFoundException("La categoría solicitada no existe o está inactiva"));
        when(catalogService.getRequestTypes(98L))
                .thenThrow(new ResourceNotFoundException("La subcategoría solicitada no existe o está inactiva"));

        mockMvc.perform(get("/api/catalog/categories/99/subcategories"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("La categoría solicitada no existe o está inactiva"));
        mockMvc.perform(get("/api/catalog/subcategories/98/request-types"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("La subcategoría solicitada no existe o está inactiva"));
    }

    @Test
    void existingRequestTypeFormEndpointStillWorks() throws Exception {
        FormDefinitionResponse form = new FormDefinitionResponse();
        form.setRequestTypeId(3L);
        form.setRequestTypeCode("INFORMAR_UN_BACHE");
        form.setVersion(1);
        form.setFields(List.of());
        when(formService.getFormForRequestType(3L)).thenReturn(form);

        mockMvc.perform(get("/api/catalog/request-types/3/form"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestTypeId").value(3))
                .andExpect(jsonPath("$.requestTypeCode").value("INFORMAR_UN_BACHE"))
                .andExpect(jsonPath("$.fields").isArray());
    }
}