package com.reclamos.backend.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("dev")
class NeighborhoodMigrationIntegrationTest {
    private static final Map<String, Integer> REPRESENTATIVE_POPULATIONS = Map.of(
            "Agronomía", 13912,
            "Palermo", 226534,
            "Puerto Madero", 6726,
            "Villa Lugano", 126374,
            "Villa Urquiza", 91563
    );

    private static final List<String> ACCENTED_NAMES = List.of(
            "Agronomía",
            "Constitución",
            "Núñez",
            "San Cristóbal",
            "San Nicolás",
            "Vélez Sarsfield",
            "Villa Ortúzar",
            "Villa Pueyrredón"
    );

    @Autowired
    private DataSource dataSource;

    @Test
    void cleanV1ToV19SeedsAllCabaNeighborhoods() {
        String schema = temporarySchema();
        JdbcTemplate database = new JdbcTemplate(dataSource);
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .cleanDisabled(false)
                    .target(MigrationVersion.fromVersion("19"))
                    .load()
                    .migrate();

            assertEquals(48, count(database, schema, "COUNT(*)"));
            assertEquals(48, count(database, schema, "COUNT(DISTINCT name)"));
            assertEquals(0, count(database, schema, "COUNT(*) FILTER (WHERE population IS NULL)"));
            assertEquals(0, count(database, schema, "COUNT(*) FILTER (WHERE population <= 0)"));

            REPRESENTATIVE_POPULATIONS.forEach((name, population) -> assertEquals(population,
                    database.queryForObject("SELECT population FROM " + schema + ".neighborhood WHERE name=?",
                            Integer.class, name)));

            assertEquals(Set.copyOf(ACCENTED_NAMES), Set.copyOf(database.queryForList(
                    "SELECT name FROM " + schema + ".neighborhood WHERE name IN (" +
                            String.join(",", ACCENTED_NAMES.stream().map(ignored -> "?").toList()) + ")",
                    String.class, ACCENTED_NAMES.toArray())));
        } finally {
            database.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private int count(JdbcTemplate database, String schema, String expression) {
        return database.queryForObject("SELECT " + expression + " FROM " + schema + ".neighborhood", Integer.class);
    }

    private String temporarySchema() {
        return "neighborhood_" + UUID.randomUUID().toString().replace("-", "");
    }
}