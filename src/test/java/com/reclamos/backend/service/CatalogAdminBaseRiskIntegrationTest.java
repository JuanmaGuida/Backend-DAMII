package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.RequestTypeAdminRequest;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.FormField;
import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.FormTemplate;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.RiskOperator;
import com.reclamos.backend.entity.RiskRule;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.exception.InvalidCatalogRequestException;
import com.reclamos.backend.repository.CategoryRepository;
import com.reclamos.backend.repository.FormFieldRepository;
import com.reclamos.backend.repository.FormTemplateRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.RiskRuleRepository;
import com.reclamos.backend.repository.SubcategoryRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("dev")
class CatalogAdminBaseRiskIntegrationTest {
    @Autowired
    private CatalogAdminService catalogAdminService;
    @Autowired
    private RiskCalculationService riskCalculationService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private SubcategoryRepository subcategoryRepository;
    @Autowired
    private RequestTypeRepository requestTypeRepository;
    @Autowired
    private FormTemplateRepository formTemplateRepository;
    @Autowired
    private FormFieldRepository formFieldRepository;
    @Autowired
    private RiskRuleRepository riskRuleRepository;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void baseRiskIsEditableUntilFirstUseAndThenHistoricalRiskAndConfigurationRemainAtomic() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Category category = categoryRepository.save(category("BaseRiskCategory" + suffix));
        Subcategory subcategory = subcategoryRepository.save(subcategory(category, "BaseRiskSubcategory" + suffix));
        RequestType requestType = requestTypeRepository.save(requestType(subcategory, suffix));

        try {
            RequestTypeAdminRequest unusedUpdate = request(requestType, "Unused update", Risk.MEDIUM);
            catalogAdminService.updateRequestType(requestType.getId(), unusedUpdate);
            assertEquals(Risk.MEDIUM, requestTypeRepository.findById(requestType.getId()).orElseThrow().getBaseRisk());

            FormTemplate template = new FormTemplate();
            template.setRequestType(requestType);
            template.setVersion(1);
            template = formTemplateRepository.save(template);

            FormField field = new FormField();
            field.setFormTemplate(template);
            field.setCode("urgent");
            field.setLabel("Urgente");
            field.setType(FormFieldType.BOOLEAN);
            field.setRequired(true);
            field.setAllowUnknown(false);
            field.setDisplayOrder(1);
            field = formFieldRepository.save(field);

            RiskRule rule = new RiskRule();
            rule.setFormField(field);
            rule.setOperator(RiskOperator.EQUALS);
            rule.setExpectedValue(true);
            rule.setRiskIncrement((short) 30);
            rule = riskRuleRepository.save(rule);

            Ticket ticket = ticketRepository.save(ticket(requestType, template, suffix));
            RiskAssessment riskBefore = calculate(ticket.getId());
            Map<String, Object> ticketBefore = jdbcTemplate.queryForMap(
                    "SELECT request_type_id, form_template_id, form_data::text AS form_data, updated_at "
                            + "FROM tickets WHERE id = ?",
                    ticket.getId());
            Map<String, Object> templateBefore = jdbcTemplate.queryForMap(
                    "SELECT request_type_id, version, active FROM form_templates WHERE id = ?", template.getId());
            Map<String, Object> fieldBefore = jdbcTemplate.queryForMap(
                    "SELECT form_template_id, code, label, type, required, allow_unknown, display_order, "
                            + "config::text AS config FROM form_fields WHERE id = ?",
                    field.getId());
            Map<String, Object> ruleBefore = jdbcTemplate.queryForMap(
                    "SELECT form_field_id, operator, expected_value::text AS expected_value, risk_increment, active "
                            + "FROM risk_rules WHERE id = ?",
                    rule.getId());
            Integer requestTypeCountBefore = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM request_types", Integer.class);

            RequestTypeAdminRequest sameRiskUpdate = request(requestType, "Used update allowed", Risk.MEDIUM);
            catalogAdminService.updateRequestType(requestType.getId(), sameRiskUpdate);
            assertEquals("Used update allowed",
                    requestTypeRepository.findById(requestType.getId()).orElseThrow().getName());

            RequestTypeAdminRequest rejectedUpdate = request(requestType, "Must not persist", Risk.CRITICAL);
            rejectedUpdate.setCode("REJECTED_" + suffix);
            assertThrows(InvalidCatalogRequestException.class,
                    () -> catalogAdminService.updateRequestType(requestType.getId(), rejectedUpdate));

            RequestType persisted = requestTypeRepository.findById(requestType.getId()).orElseThrow();
            assertEquals(Risk.MEDIUM, persisted.getBaseRisk());
            assertEquals("Used update allowed", persisted.getName());
            assertEquals("BASERISK_" + suffix, persisted.getCode());
            assertEquals(ticketBefore, jdbcTemplate.queryForMap(
                    "SELECT request_type_id, form_template_id, form_data::text AS form_data, updated_at "
                            + "FROM tickets WHERE id = ?",
                    ticket.getId()));
            assertEquals(templateBefore, jdbcTemplate.queryForMap(
                    "SELECT request_type_id, version, active FROM form_templates WHERE id = ?", template.getId()));
            assertEquals(fieldBefore, jdbcTemplate.queryForMap(
                    "SELECT form_template_id, code, label, type, required, allow_unknown, display_order, "
                            + "config::text AS config FROM form_fields WHERE id = ?",
                    field.getId()));
            assertEquals(ruleBefore, jdbcTemplate.queryForMap(
                    "SELECT form_field_id, operator, expected_value::text AS expected_value, risk_increment, active "
                            + "FROM risk_rules WHERE id = ?",
                    rule.getId()));
            assertEquals(requestTypeCountBefore,
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_types", Integer.class));
            assertEquals(riskBefore, calculate(ticket.getId()));
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM tickets WHERE request_type_id IN (SELECT id FROM request_types WHERE code = ?)",
                    "BASERISK_" + suffix);
            jdbcTemplate.update(
                    "DELETE FROM risk_rules WHERE form_field_id IN (SELECT ff.id FROM form_fields ff "
                            + "JOIN form_templates ft ON ft.id = ff.form_template_id "
                            + "JOIN request_types rt ON rt.id = ft.request_type_id WHERE rt.code = ?)",
                    "BASERISK_" + suffix);
            jdbcTemplate.update(
                    "DELETE FROM form_fields WHERE form_template_id IN (SELECT ft.id FROM form_templates ft "
                            + "JOIN request_types rt ON rt.id = ft.request_type_id WHERE rt.code = ?)",
                    "BASERISK_" + suffix);
            jdbcTemplate.update(
                    "DELETE FROM form_templates WHERE request_type_id IN "
                            + "(SELECT id FROM request_types WHERE code = ?)",
                    "BASERISK_" + suffix);
            jdbcTemplate.update("DELETE FROM request_types WHERE code = ?", "BASERISK_" + suffix);
            jdbcTemplate.update("DELETE FROM subcategories WHERE id = ?", subcategory.getId());
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", category.getId());
        }
    }

    private RiskAssessment calculate(UUID ticketId) {
        return transactions.execute(status -> riskCalculationService.calculateRisk(
                ticketRepository.findById(ticketId).orElseThrow()));
    }

    private Category category(String name) {
        Category category = new Category();
        category.setName(name);
        category.setDescription("base risk integration test");
        return category;
    }

    private Subcategory subcategory(Category category, String name) {
        Subcategory subcategory = new Subcategory();
        subcategory.setCategory(category);
        subcategory.setName(name);
        subcategory.setDescription("base risk integration test");
        return subcategory;
    }

    private RequestType requestType(Subcategory subcategory, String suffix) {
        RequestType requestType = new RequestType();
        requestType.setSubcategory(subcategory);
        requestType.setCode("BASERISK_" + suffix);
        requestType.setName("Base Risk " + suffix);
        requestType.setDescription("base risk integration test");
        requestType.setTicketType(TicketType.COMPLAINT);
        requestType.setResponsibleAreaId("M2");
        requestType.setMinimumPriority(Priority.LOW);
        requestType.setBaseRisk(Risk.LOW);
        requestType.setAffectedPopulationFactor(BigDecimal.ZERO);
        requestType.setAllowsAnonymous(true);
        return requestType;
    }

    private Ticket ticket(RequestType requestType, FormTemplate template, String suffix) {
        Ticket ticket = new Ticket();
        ticket.setPublicId("BR-" + suffix.substring(0, 20));
        ticket.setTrackingCodeHash("base-risk-hash-" + suffix);
        ticket.setAnonymous(true);
        ticket.setRequestType(requestType);
        ticket.setFormTemplateId(template.getId());
        ticket.setTicketType(requestType.getTicketType());
        ticket.setResponsibleAreaId(requestType.getResponsibleAreaId());
        ticket.setSummary("Base risk history test");
        ticket.setDescription("Base risk history test");
        ticket.setFormData(Map.of("urgent", true));
        ticket.setCurrentStatus(TicketStatus.REGISTERED);
        ticket.setCurrentPriority(Priority.HIGH);
        ticket.setStatusChangedAt(Instant.now());
        return ticket;
    }

    private RequestTypeAdminRequest request(RequestType requestType, String name, Risk baseRisk) {
        RequestTypeAdminRequest request = new RequestTypeAdminRequest();
        request.setSubcategoryId(requestType.getSubcategory().getId());
        request.setCode(requestType.getCode());
        request.setName(name);
        request.setDescription(requestType.getDescription());
        request.setTicketType(requestType.getTicketType());
        request.setResponsibleAreaId(requestType.getResponsibleAreaId());
        request.setMinimumPriority(requestType.getMinimumPriority());
        request.setBaseRisk(baseRisk);
        request.setAffectedPopulationFactor(requestType.getAffectedPopulationFactor());
        request.setAllowsAnonymous(requestType.isAllowsAnonymous());
        request.setRequiresLocation(requestType.isRequiresLocation());
        return request;
    }
}
