package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.FormFieldAdminRequest;
import com.reclamos.backend.dto.request.FormTemplateAdminRequest;
import com.reclamos.backend.dto.response.FormTemplateAdminResponse;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.repository.CategoryRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.SubcategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA (FAIL de "consulta y actualización del schema de Request Types"):
 * regresión contra la base real. {@link FormAdminServiceTest} (con mocks) no
 * puede detectar este bug porque el conflicto es específicamente contra
 * uk_form_template_active_request_type (índice único parcial, V3) combinado
 * con el timing del INSERT inmediato que impone
 * FormTemplate.id GenerationType.IDENTITY — ninguna de las dos cosas existe
 * en un mock. Antes del fix, el segundo saveForm() de este test tiraba
 * DataIntegrityViolationException.
 */
@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class FormAdminServiceVersioningIntegrationTest {
    @Autowired
    private FormAdminService formAdminService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private SubcategoryRepository subcategoryRepository;
    @Autowired
    private RequestTypeRepository requestTypeRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMvc mvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    void putCreatesTheNextVersionAndKeepsServerManagedFields() throws Exception {
        Category category = categoryRepository.save(category());
        Subcategory subcategory = subcategoryRepository.save(subcategory(category));
        RequestType requestType = requestTypeRepository.save(requestType(subcategory));

        try {
            FormTemplateAdminResponse v1 = formAdminService.saveForm(requestType.getId(),
                    templateRequest());
            assertEquals(1, v1.getVersion());
            assertTrue(v1.isActive());

            Long previousField = v1.getFields().getFirst().getId();
            mvc.perform(put("/api/admin/catalog/request-types/{id}/form", requestType.getId())
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {
                                      "id": %d,
                                      "version": 99,
                                      "active": false,
                                      "fields": [{
                                        "id": %d,
                                        "formTemplateId": %d,
                                        "code": "DESCRIPCION",
                                        "label": "Detalle actualizado",
                                        "type": "TEXT",
                                        "displayOrder": 1
                                      }]
                                    }
                                    """.formatted(v1.getId(), previousField, v1.getId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.active").value(true));

            assertEquals("Descripción", jdbcTemplate.queryForObject(
                    "SELECT label FROM form_fields WHERE id = ? AND form_template_id = ?",
                    String.class, previousField, v1.getId()));
            assertEquals(Boolean.FALSE, jdbcTemplate.queryForObject(
                    "SELECT active FROM form_templates WHERE id = ?", Boolean.class, v1.getId()));
            assertEquals("Detalle actualizado", jdbcTemplate.queryForObject("""
                    SELECT f.label FROM form_fields f JOIN form_templates t ON t.id = f.form_template_id
                    WHERE t.request_type_id = ? AND t.version = 2 AND t.active
                    """, String.class, requestType.getId()));
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM form_templates WHERE request_type_id = ? AND active = TRUE",
                    Integer.class, requestType.getId()));
            assertEquals(2, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM form_templates WHERE request_type_id = ?",
                    Integer.class, requestType.getId()));
        } finally {
            jdbcTemplate.update("DELETE FROM form_fields WHERE form_template_id IN "
                    + "(SELECT id FROM form_templates WHERE request_type_id = ?)", requestType.getId());
            jdbcTemplate.update("DELETE FROM form_templates WHERE request_type_id = ?", requestType.getId());
            requestTypeRepository.deleteById(requestType.getId());
            subcategoryRepository.deleteById(subcategory.getId());
            categoryRepository.deleteById(category.getId());
        }
    }

    private FormTemplateAdminRequest templateRequest() {
        FormFieldAdminRequest field = new FormFieldAdminRequest();
        field.setCode("DESCRIPCION");
        field.setLabel("Descripción");
        field.setType(FormFieldType.TEXT);
        field.setRequired(false);
        field.setDisplayOrder(1);
        FormTemplateAdminRequest request = new FormTemplateAdminRequest();
        request.setFields(List.of(field));
        return request;
    }

    private Category category() {
        Category category = new Category();
        category.setName("Test versionado " + UUID.randomUUID());
        category.setDescription("desc");
        return category;
    }

    private Subcategory subcategory(Category category) {
        Subcategory subcategory = new Subcategory();
        subcategory.setCategory(category);
        subcategory.setName("Test versionado " + UUID.randomUUID());
        subcategory.setDescription("desc");
        return subcategory;
    }

    private RequestType requestType(Subcategory subcategory) {
        RequestType requestType = new RequestType();
        requestType.setSubcategory(subcategory);
        requestType.setCode("TST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        requestType.setName("Test versionado " + UUID.randomUUID());
        requestType.setDescription("desc");
        requestType.setTicketType(TicketType.COMPLAINT);
        requestType.setResponsibleAreaId("M2");
        requestType.setMinimumPriority(Priority.LOW);
        requestType.setBaseRisk(Risk.LOW);
        requestType.setAffectedPopulationFactor(BigDecimal.ZERO);
        requestType.setAllowsAnonymous(true);
        requestType.setRequiresLocation(false);
        return requestType;
    }
}
