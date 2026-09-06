package com.reclamos.backend;

import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.RiskRule;
import com.reclamos.backend.repository.RiskRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class RiskRuleMigrationIntegrationTest {
    @Autowired
    private RiskRuleRepository riskRuleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationProducesApprovedRulesAndTypedExpectedValues() {
        List<RiskRule> rules = riskRuleRepository.findAll();

        assertEquals(303, rules.size());
        assertEquals(Map.of(
                        (short) 5, 35L,
                        (short) 10, 39L,
                        (short) 13, 10L,
                        (short) 18, 54L,
                        (short) 27, 75L,
                        (short) 36, 90L),
                rules.stream().collect(Collectors.groupingBy(
                        RiskRule::getRiskIncrement, Collectors.counting())));
        assertEquals(78, rules.stream()
                .filter(rule -> rule.getFormField().getType() == FormFieldType.BOOLEAN)
                .filter(rule -> Boolean.TRUE.equals(rule.getExpectedValue()))
                .count());
        assertEquals(225, rules.stream()
                .filter(rule -> rule.getFormField().getType() == FormFieldType.SELECT)
                .filter(rule -> rule.getExpectedValue() instanceof String)
                .count());
        assertTrue(rules.stream().allMatch(RiskRule::isActive));
    }

    @Test
    void migrationRemovesLegacySchemaAndConfig() {
        Integer residualRiskScores = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM form_fields "
                        + "WHERE jsonb_path_exists(config, '$.**.riskScore')",
                Integer.class);
        Integer requiredColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='form_fields' "
                        + "AND column_name='required'",
                Integer.class);
        Integer templateIdColumns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='tickets' "
                        + "AND column_name='form_template_id'",
                Integer.class);

        assertEquals(0, residualRiskScores);
        assertEquals(0, requiredColumns);
        assertEquals(1, templateIdColumns);
    }
}
