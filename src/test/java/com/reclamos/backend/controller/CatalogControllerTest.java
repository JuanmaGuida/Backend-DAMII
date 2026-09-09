package com.reclamos.backend.controller;

import com.reclamos.backend.dto.response.CategoryResponse;
import com.reclamos.backend.dto.response.FormDefinitionResponse;
import com.reclamos.backend.dto.response.RequestTypeResponse;
import com.reclamos.backend.dto.response.SubcategoryResponse;
import com.reclamos.backend.dto.response.NeighborhoodResponse;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.exception.GlobalExceptionHandler;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.service.CatalogService;
import com.reclamos.backend.service.FormService;
import com.reclamos.backend.service.NeighborhoodService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CatalogControllerTest {
    @Mock
    private CatalogService catalogService;
    @Mock
    private NeighborhoodService neighborhoodService;
    @Mock
    private FormService formService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new CatalogController(catalogService, neighborhoodService),
                        new CatalogFormController(formService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listsNeighborhoodsAsJsonWithoutAdditionalFields() throws Exception {
        UUID id = UUID.fromString("0c02dffa-8a9c-5007-bce1-2b46669dc7d8");
        NeighborhoodResponse neighborhood = new NeighborhoodResponse();
        neighborhood.setId(id);
        neighborhood.setName("Agronomía");
        neighborhood.setPopulation(13912);
        when(neighborhoodService.findAll()).thenReturn(List.of(neighborhood));

        mockMvc.perform(get("/api/catalog/neighborhoods"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].length()").value(3))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].name").value("Agronomía"))
                .andExpect(jsonPath("$[0].population").value(13912));
    }

    @Test
    void emptyNeighborhoodCatalogReturnsEmptyArray() throws Exception {
        when(neighborhoodService.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/catalog/neighborhoods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getsNeighborhoodByIdWithoutAdditionalFields() throws Exception {
        UUID id = UUID.fromString("0c02dffa-8a9c-5007-bce1-2b46669dc7d8");
        NeighborhoodResponse neighborhood = new NeighborhoodResponse();
        neighborhood.setId(id);
        neighborhood.setName("Agronomía");
        neighborhood.setPopulation(13912);
        when(neighborhoodService.findById(id)).thenReturn(neighborhood);

        mockMvc.perform(get("/api/catalog/neighborhoods/{neighborhoodId}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Agronomía"))
                .andExpect(jsonPath("$.population").value(13912));
    }

    @Test
    void missingNeighborhoodReturnsCanonicalNotFound() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000099");
        when(neighborhoodService.findById(id))
                .thenThrow(new ResourceNotFoundException("No se encontró el barrio solicitado."));

        mockMvc.perform(get("/api/catalog/neighborhoods/{neighborhoodId}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No se encontró el barrio solicitado."))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void invalidNeighborhoodIdReturnsCanonicalBadRequest() throws Exception {
        mockMvc.perform(get("/api/catalog/neighborhoods/no-es-un-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("El parámetro 'neighborhoodId' tiene un valor inválido"))
                .andExpect(jsonPath("$.length()").value(2));
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
        requestType.setResponsibleAreaId("M3");
        RequestTypeResponse lighting = new RequestTypeResponse();
        lighting.setId(4L);
        lighting.setCode("INFORMAR_UNA_LUMINARIA_APAGADA");
        lighting.setName("Informar una luminaria apagada");
        lighting.setTicketType(TicketType.COMPLAINT);
        lighting.setResponsibleAreaId("M6");
        when(catalogService.getRequestTypes(2L)).thenReturn(List.of(requestType, lighting));

        mockMvc.perform(get("/api/catalog/subcategories/2/request-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("INFORMAR_UN_BACHE"))
                .andExpect(jsonPath("$[0].ticketType").value("COMPLAINT"))
                .andExpect(jsonPath("$[0].responsibleAreaId").value("M3"))
                .andExpect(jsonPath("$[1].responsibleAreaId").value("M6"))
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
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.message").value("La categoría solicitada no existe o está inactiva"));
        mockMvc.perform(get("/api/catalog/subcategories/98/request-types"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.message").value("La subcategoría solicitada no existe o está inactiva"));
    }

    @Test
    void invalidPathVariableUsesCanonicalBadRequest() throws Exception {
        mockMvc.perform(get("/api/catalog/categories/not-a-number/subcategories"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("El parámetro 'categoryId' tiene un valor inválido"))
                .andExpect(jsonPath("$.length()").value(2));
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
