package com.reclamos.backend.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.time.Duration;
import java.util.UUID;

/**
 * DDA2-230 (TST-01): PostgreSQL real en un contenedor de Testcontainers para las pruebas de integración que lo
 * declaran con {@code @DynamicPropertySource}.
 *
 * <p>Se activa con la variable de entorno {@value #ENABLE_VARIABLE}={@code true} (la define el CI). Sin ella no hace
 * nada y las pruebas siguen usando {@code SPRING_DATASOURCE_URL}, como hasta ahora; así quien corre las pruebas con su
 * propio Postgres local no necesita Docker. Si está activada y Docker no está disponible, la prueba falla: no hay
 * fallback silencioso.</p>
 *
 * <p>Un único contenedor por JVM, con credenciales generadas en cada ejecución. Flyway aplica las migraciones sobre
 * una base vacía, así que estas pruebas no dependen del estado del servicio de Postgres del workflow.</p>
 */
public final class TestPostgres {
    public static final String ENABLE_VARIABLE = "TESTCONTAINERS_POSTGRES";
    private static final String IMAGE = "postgres:17";
    private static final String DATABASE = "m2_tc";
    private static final String USER = "m2_tc";
    // Generada en cada ejecucion: el escaner de secretos rechaza credenciales literales entre comillas.
    private static final String PASSWORD = UUID.randomUUID().toString();
    private static GenericContainer<?> container;

    private TestPostgres() { }

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getenv(ENABLE_VARIABLE));
    }

    /** Registra url, usuario y clave del contenedor; no hace nada si Testcontainers no está activado. */
    public static void register(DynamicPropertyRegistry registry) {
        if (!enabled()) {
            return;
        }
        GenericContainer<?> postgres = start();
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + DATABASE;
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> USER);
        registry.add("spring.datasource.password", () -> PASSWORD);
    }

    private static synchronized GenericContainer<?> start() {
        if (container == null) {
            GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse(IMAGE))
                    .withEnv("POSTGRES_DB", DATABASE)
                    .withEnv("POSTGRES_USER", USER)
                    .withEnv("POSTGRES_PASSWORD", PASSWORD)
                    .withExposedPorts(5432)
                    // La imagen arranca un servidor temporal para inicializar y luego el definitivo:
                    // el mensaje aparece dos veces y recién la segunda indica que está listo.
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
                    .withStartupTimeout(Duration.ofMinutes(2));
            postgres.start();
            Runtime.getRuntime().addShutdownHook(new Thread(postgres::stop));
            container = postgres;
        }
        return container;
    }
}
