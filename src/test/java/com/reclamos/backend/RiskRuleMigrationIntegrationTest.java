package com.reclamos.backend;

import com.reclamos.backend.entity.FormFieldType;
import com.reclamos.backend.entity.RiskOperator;
import com.reclamos.backend.entity.RiskRule;
import com.reclamos.backend.repository.RiskRuleRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;

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

    @Autowired
    private DataSource dataSource;

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
        assertEquals(Set.of(FormFieldType.BOOLEAN, FormFieldType.SELECT), rules.stream()
                .map(rule -> rule.getFormField().getType())
                .collect(Collectors.toSet()));
        assertTrue(rules.stream().allMatch(rule -> rule.getOperator() == RiskOperator.EQUALS));
        assertEquals(rules.size(), rules.stream()
                .map(rule -> rule.getFormField().getId() + "|" + rule.getExpectedValue())
                .distinct()
                .count());
    }

    @Test
    void migrationRemovesLegacyConfigAndAddsAnswerRequirementColumnsWithTrueDefaults() {
        String schema = "risk_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .target(MigrationVersion.fromVersion("12"))
                    .load()
                    .migrate();

            Integer residualRiskScores = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".form_fields "
                            + "WHERE jsonb_path_exists(config, '$.**.riskScore')",
                    Integer.class);
            Integer requirementColumns = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema=? AND table_name='form_fields' "
                            + "AND column_name IN ('required', 'allow_unknown')",
                    Integer.class, schema);
            Integer fieldsWithoutRequiredDefaults = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema + ".form_fields WHERE NOT required OR NOT allow_unknown",
                    Integer.class);
            Integer trueDefaults = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema=? AND table_name='form_fields' "
                            + "AND column_name IN ('required', 'allow_unknown') AND column_default='true'",
                    Integer.class, schema);
            Integer templateIdColumns = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_schema=? AND table_name='tickets' AND column_name='form_template_id'",
                    Integer.class, schema);

            assertEquals(0, residualRiskScores);
            assertEquals(2, requirementColumns);
            assertEquals(0, fieldsWithoutRequiredDefaults);
            assertEquals(2, trueDefaults);
            assertEquals(1, templateIdColumns);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
