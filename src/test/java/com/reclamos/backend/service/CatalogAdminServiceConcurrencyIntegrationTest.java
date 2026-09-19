package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CategoryAdminRequest;
import com.reclamos.backend.repository.CategoryRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.SubcategoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * QA (FAIL de "validaciones de datos y relaciones jerárquicas del
 * catálogo"): "se reprodujo un 500 al hacer altas concurrentes con el mismo
 * nombre o código". CatalogAdminService.createCategory hace un pre-check
 * existsByNameIgnoreCase antes de guardar, que es TOCTOU-racy: dos
 * transacciones pueden pasar el pre-check al mismo tiempo y sólo la
 * constraint de base (uk_category_name) frena a la segunda. Se fuerza
 * determinísticamente esa carrera (mismo patrón que
 * ModuleUserConcurrencyIntegrationTest: un mock que delega al repository
 * real pero sincroniza ambos pre-checks con un CyclicBarrier antes de dejar
 * avanzar a cualquiera de los dos) para probar que la segunda transacción
 * SIEMPRE falla con DataIntegrityViolationException — nunca con un
 * duplicado silencioso ni con un 500 sin clasificar. La pieza que faltaba
 * era GlobalExceptionHandler mapeando esa excepción a un 409 controlado
 * (ver GlobalExceptionHandlerTest.dataIntegrityViolationMapsToControlledConflict).
 */
@SpringBootTest
@ActiveProfiles("dev")
class CatalogAdminServiceConcurrencyIntegrationTest {
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private SubcategoryRepository subcategoryRepository;
    @Autowired
    private RequestTypeRepository requestTypeRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void concurrentCreatesWithTheSameNameLeaveExactlyOneCategoryAndFailTheOtherWithDataIntegrityViolation()
            throws Exception {
        String name = "Concurrencia " + UUID.randomUUID();
        CyclicBarrier preCheckBarrier = new CyclicBarrier(2);
        CategoryRepository coordinatedRepository = mock(CategoryRepository.class, delegatesTo(categoryRepository));
        doAnswer(invocation -> {
            preCheckBarrier.await(10, TimeUnit.SECONDS);
            return categoryRepository.existsByNameIgnoreCase(name);
        }).when(coordinatedRepository).existsByNameIgnoreCase(name);
        CatalogAdminService service =
                new CatalogAdminService(coordinatedRepository, subcategoryRepository, requestTypeRepository);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Exception> first = executor.submit(() -> attemptCreate(service, name));
            Future<Exception> second = executor.submit(() -> attemptCreate(service, name));

            Exception firstOutcome = first.get(15, TimeUnit.SECONDS);
            Exception secondOutcome = second.get(15, TimeUnit.SECONDS);
            long successes = Stream.of(firstOutcome, secondOutcome).filter(outcome -> outcome == null).count();
            long integrityFailures = Stream.of(firstOutcome, secondOutcome)
                    .filter(outcome -> outcome instanceof DataIntegrityViolationException)
                    .count();

            assertEquals(1, successes, "exactamente una de las dos altas concurrentes debe persistir");
            assertEquals(1, integrityFailures,
                    "la otra debe fallar con DataIntegrityViolationException, no en silencio ni con otra excepción");
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM categories WHERE name = ?", Integer.class, name));
        } finally {
            executor.shutdownNow();
            jdbcTemplate.update("DELETE FROM categories WHERE name = ?", name);
        }
    }

    private Exception attemptCreate(CatalogAdminService service, String name) {
        try {
            service.createCategory(categoryRequest(name));
            return null;
        } catch (Exception exception) {
            return exception;
        }
    }

    private CategoryAdminRequest categoryRequest(String name) {
        CategoryAdminRequest request = new CategoryAdminRequest();
        request.setName(name);
        request.setDescription("desc");
        return request;
    }
}
