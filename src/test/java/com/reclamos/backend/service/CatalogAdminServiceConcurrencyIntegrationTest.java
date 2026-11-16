package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CategoryAdminRequest;
import com.reclamos.backend.dto.request.RequestTypeAdminRequest;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.exception.InvalidCatalogRequestException;
import com.reclamos.backend.repository.CategoryRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.SubcategoryRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * QA (FAIL de "validaciones de datos y relaciones jerárquicas del
 * catálogo", primera vuelta): "se reprodujo un 500 al hacer altas
 * concurrentes con el mismo nombre o código". CatalogAdminService.create*
 * hace un pre-check existsBy...IgnoreCase antes de guardar, que es
 * TOCTOU-racy: dos transacciones pueden pasar el pre-check al mismo tiempo y
 * sólo la constraint de base frena a la segunda. Se fuerza
 * determinísticamente esa carrera (mismo patrón que
 * ModuleUserConcurrencyIntegrationTest: un mock que delega al repository
 * real pero sincroniza ambos pre-checks con un CyclicBarrier antes de dejar
 * avanzar a cualquiera de los dos) para probar que la segunda transacción
 * SIEMPRE falla con DataIntegrityViolationException — nunca con un
 * duplicado silencioso ni con un 500 sin clasificar. La pieza que faltaba
 * era GlobalExceptionHandler mapeando esa excepción a un 409 controlado
 * (ver GlobalExceptionHandlerTest.dataIntegrityViolationMapsToControlledConflict).
 *
 * QA (segunda vuelta, sobre el mismo ticket): con el fix de arriba ya
 * aplicado, el 500 desapareció, pero "dos altas simultáneas que sólo
 * difieren en mayúsculas pueden quedar guardadas las dos aunque el sistema
 * las considera duplicadas" — uk_category_name, uk_subcategory_category_name
 * y uk_request_type_code eran UNIQUE case-sensitive, mientras que el
 * pre-check de la aplicación siempre fue ...IgnoreCase. V36 reemplaza esas
 * constraints por índices únicos funcionales sobre LOWER(...), que es
 * exactamente el mismo criterio que ya usa el pre-check. Los métodos
 * *CaseVariant* de abajo reproducen esa carrera específica (mismo nombre o
 * código salvo mayúsculas, no idéntico byte a byte) para probar que V36
 * efectivamente la cierra: el stub del mock ahora usa un matcher any(),
 * generalizado para poder sincronizar dos invocaciones con argumentos
 * DISTINTOS (antes sólo hacía falta sincronizar el mismo nombre repetido).
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
    private TicketRepository ticketRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactions;

    @Test
    void concurrentCreatesWithTheSameNameLeaveExactlyOneCategoryAndFailTheOtherWithDataIntegrityViolation()
            throws Exception {
        String name = "Concurrencia " + UUID.randomUUID();
        CyclicBarrier preCheckBarrier = new CyclicBarrier(2);
        CategoryRepository coordinatedRepository = mock(CategoryRepository.class, delegatesTo(categoryRepository));
        doAnswer(invocation -> {
            String actualName = invocation.getArgument(0);
            boolean exists = categoryRepository.existsByNameIgnoreCase(actualName);
            preCheckBarrier.await(10, TimeUnit.SECONDS);
            return exists;
        }).when(coordinatedRepository).existsByNameIgnoreCase(any());
        CatalogAdminService service =
                new CatalogAdminService(
                        coordinatedRepository, subcategoryRepository, requestTypeRepository, ticketRepository);
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

    /**
     * QA (segunda vuelta): mismo escenario que el test anterior, pero las
     * dos altas concurrentes NO usan el mismo nombre — difieren sólo en
     * mayúsculas ("...abc" vs "...ABC"). Antes de V36, uk_category_name era
     * case-sensitive, así que la constraint de base no las distinguía como
     * duplicadas: las dos pasaban el pre-check (existsByNameIgnoreCase, que
     * sí las considera iguales) Y las dos insertaban sin error, quedando dos
     * filas que la aplicación trata como el mismo nombre. Con
     * uk_category_name_ci (índice único funcional sobre LOWER(name)) la
     * segunda inserción debe fallar igual que en el caso de nombre idéntico.
     */
    @Test
    void concurrentCreatesWithCaseVariantNamesLeaveExactlyOneCategoryAndFailTheOtherWithDataIntegrityViolation()
            throws Exception {
        String base = "ConcurrenciaCI" + UUID.randomUUID().toString().replace("-", "");
        String nameLower = base.toLowerCase();
        String nameUpper = base.toUpperCase();
        CyclicBarrier preCheckBarrier = new CyclicBarrier(2);
        CategoryRepository coordinatedRepository = mock(CategoryRepository.class, delegatesTo(categoryRepository));
        doAnswer(invocation -> {
            String actualName = invocation.getArgument(0);
            boolean exists = categoryRepository.existsByNameIgnoreCase(actualName);
            preCheckBarrier.await(10, TimeUnit.SECONDS);
            return exists;
        }).when(coordinatedRepository).existsByNameIgnoreCase(any());
        CatalogAdminService service =
                new CatalogAdminService(
                        coordinatedRepository, subcategoryRepository, requestTypeRepository, ticketRepository);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Exception> first = executor.submit(() -> attemptCreate(service, nameLower));
            Future<Exception> second = executor.submit(() -> attemptCreate(service, nameUpper));

            Exception firstOutcome = first.get(15, TimeUnit.SECONDS);
            Exception secondOutcome = second.get(15, TimeUnit.SECONDS);
            long successes = Stream.of(firstOutcome, secondOutcome).filter(outcome -> outcome == null).count();
            long integrityFailures = Stream.of(firstOutcome, secondOutcome)
                    .filter(outcome -> outcome instanceof DataIntegrityViolationException)
                    .count();

            assertEquals(1, successes,
                    "de dos altas concurrentes que sólo difieren en mayúsculas, exactamente una debe persistir");
            assertEquals(1, integrityFailures,
                    "la otra debe fallar con DataIntegrityViolationException — antes de V36 quedaban las dos "
                            + "guardadas porque uk_category_name era case-sensitive");
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM categories WHERE LOWER(name) = LOWER(?)", Integer.class, nameLower));
        } finally {
            executor.shutdownNow();
            jdbcTemplate.update("DELETE FROM categories WHERE LOWER(name) = LOWER(?)", nameLower);
        }
    }

    /**
     * Mismo fix (V36), sobre uk_request_type_code_ci en vez de
     * uk_category_name_ci: dos Request Types concurrentes con códigos que
     * sólo difieren en mayúsculas. Los nombres son distintos entre sí (y
     * ambos activos) para que la única carrera forzada sea la de código —
     * el pre-check de nombre no está sincronizado y no debería interferir.
     *
     * La serialización jerárquica toma Category/Subcategory antes del
     * pre-check de código. Por eso ambos hilos se largan juntos con una
     * barrera externa, pero ya no se fuerza artificialmente que los dos
     * atraviesen el pre-check: uno persiste y el otro observa el duplicado
     * tras adquirir los locks (o queda frenado por la constraint). En ambos
     * casos el conflicto es controlado y nunca quedan dos códigos equivalentes.
     */
    @Test
    void concurrentCreatesWithCaseVariantCodesLeaveExactlyOneRequestTypeAndFailTheOtherWithControlledConflict()
            throws Exception {
        Category category = categoryRepository.save(newCategory("CategoriaCI" + UUID.randomUUID()));
        Subcategory subcategory =
                subcategoryRepository.save(newSubcategory(category, "SubcategoriaCI" + UUID.randomUUID()));
        String baseCode = "RTCI" + UUID.randomUUID().toString().replace("-", "");
        String codeLower = baseCode.toLowerCase();
        String codeUpper = baseCode.toUpperCase();
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        CatalogAdminService service =
                new CatalogAdminService(
                        categoryRepository, subcategoryRepository, requestTypeRepository, ticketRepository);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Exception> first = executor.submit(() -> {
                startBarrier.await(10, TimeUnit.SECONDS);
                return attemptCreateRequestType(
                        service, subcategory.getId(), codeLower, "RequestTypeCiA" + UUID.randomUUID());
            });
            Future<Exception> second = executor.submit(() -> {
                startBarrier.await(10, TimeUnit.SECONDS);
                return attemptCreateRequestType(
                        service, subcategory.getId(), codeUpper, "RequestTypeCiB" + UUID.randomUUID());
            });

            Exception firstOutcome = first.get(15, TimeUnit.SECONDS);
            Exception secondOutcome = second.get(15, TimeUnit.SECONDS);
            long successes = Stream.of(firstOutcome, secondOutcome).filter(outcome -> outcome == null).count();
            long controlledFailures = Stream.of(firstOutcome, secondOutcome)
                    .filter(outcome -> outcome instanceof InvalidCatalogRequestException
                            || outcome instanceof DataIntegrityViolationException)
                    .count();

            assertEquals(1, successes,
                    "de dos altas concurrentes que sólo difieren en mayúsculas de código, exactamente una debe "
                            + "persistir");
            assertEquals(1, controlledFailures,
                    "la otra debe fallar con un conflicto controlado, antes o en la constraint case-insensitive");
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM request_types WHERE LOWER(code) = LOWER(?)", Integer.class, codeLower));
        } finally {
            executor.shutdownNow();
            jdbcTemplate.update("DELETE FROM request_types WHERE subcategory_id = ?", subcategory.getId());
            jdbcTemplate.update("DELETE FROM subcategories WHERE id = ?", subcategory.getId());
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", category.getId());
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

    private Exception attemptCreateRequestType(CatalogAdminService service, Long subcategoryId, String code,
            String name) {
        try {
            // transactions.execute (no service.createRequestType directo):
            // ver el javadoc del test de arriba — sin una transacción propia
            // por hilo, el acceso lazy a subcategory.getCategory() dentro de
            // requireActiveSubcategory falla antes de llegar al pre-check
            // sincronizado por el CyclicBarrier.
            transactions.execute(status -> service.createRequestType(requestTypeRequest(subcategoryId, code, name)));
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

    private RequestTypeAdminRequest requestTypeRequest(Long subcategoryId, String code, String name) {
        RequestTypeAdminRequest request = new RequestTypeAdminRequest();
        request.setSubcategoryId(subcategoryId);
        request.setCode(code);
        request.setName(name);
        request.setDescription("desc");
        request.setTicketType(TicketType.COMPLAINT);
        request.setResponsibleAreaId("M1");
        request.setMinimumPriority(Priority.LOW);
        request.setBaseRisk(Risk.LOW);
        request.setAffectedPopulationFactor(new BigDecimal("0.5"));
        request.setAllowsAnonymous(false);
        request.setRequiresLocation(false);
        return request;
    }

    private Category newCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setDescription("desc");
        return category;
    }

    private Subcategory newSubcategory(Category category, String name) {
        Subcategory subcategory = new Subcategory();
        subcategory.setCategory(category);
        subcategory.setName(name);
        subcategory.setDescription("desc");
        return subcategory;
    }
}
