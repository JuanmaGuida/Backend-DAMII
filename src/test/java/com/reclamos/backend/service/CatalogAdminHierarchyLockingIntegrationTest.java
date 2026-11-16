package com.reclamos.backend.service;

import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("dev")
class CatalogAdminHierarchyLockingIntegrationTest {
    @Autowired
    private CatalogAdminService catalogAdminService;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private SubcategoryRepository subcategoryRepository;
    @Autowired
    private RequestTypeRepository requestTypeRepository;
    @Autowired
    private TicketRepository ticketRepository;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void activatingRequestTypeWhileCategoryIsDeactivatedNeverLeavesAnActiveDescendant() throws Exception {
        Hierarchy hierarchy = createHierarchy(false, false);
        runConcurrentParentDeactivationAndChildActivation(
                hierarchy,
                () -> catalogAdminService.deactivateCategory(hierarchy.categoryId()),
                service -> service.activateRequestType(hierarchy.requestTypeId()),
                false);

        assertFalse(active("categories", hierarchy.categoryId()));
        assertFalse(active("subcategories", hierarchy.subcategoryId()));
        assertFalse(active("request_types", hierarchy.requestTypeId()));
        cleanup(hierarchy);
    }

    @Test
    void activatingRequestTypeWhileSubcategoryIsDeactivatedNeverLeavesAnActiveDescendant() throws Exception {
        Hierarchy hierarchy = createHierarchy(true, false);
        runConcurrentParentDeactivationAndChildActivation(
                hierarchy,
                () -> catalogAdminService.deactivateSubcategory(hierarchy.subcategoryId()),
                service -> service.activateRequestType(hierarchy.requestTypeId()),
                true);

        assertTrue(active("categories", hierarchy.categoryId()));
        assertFalse(active("subcategories", hierarchy.subcategoryId()));
        assertFalse(active("request_types", hierarchy.requestTypeId()));
        cleanup(hierarchy);
    }

    @Test
    void activatingSubcategoryWhileCategoryIsDeactivatedNeverLeavesAnActiveChild() throws Exception {
        Hierarchy hierarchy = createHierarchy(false, false);
        runConcurrentParentDeactivationAndChildActivation(
                hierarchy,
                () -> catalogAdminService.deactivateCategory(hierarchy.categoryId()),
                service -> service.activateSubcategory(hierarchy.subcategoryId()),
                false);

        assertFalse(active("categories", hierarchy.categoryId()));
        assertFalse(active("subcategories", hierarchy.subcategoryId()));
        assertFalse(active("request_types", hierarchy.requestTypeId()));
        cleanup(hierarchy);
    }

    @Test
    void cascadesAndExplicitNonCascadingReactivationRemainUnchangedInTheDatabase() {
        Hierarchy hierarchy = createHierarchy(true, true);
        try {
            catalogAdminService.deactivateCategory(hierarchy.categoryId());
            assertFalse(active("categories", hierarchy.categoryId()));
            assertFalse(active("subcategories", hierarchy.subcategoryId()));
            assertFalse(active("request_types", hierarchy.requestTypeId()));

            catalogAdminService.activateCategory(hierarchy.categoryId());
            assertTrue(active("categories", hierarchy.categoryId()));
            assertFalse(active("subcategories", hierarchy.subcategoryId()));
            assertFalse(active("request_types", hierarchy.requestTypeId()));

            catalogAdminService.activateSubcategory(hierarchy.subcategoryId());
            assertTrue(active("subcategories", hierarchy.subcategoryId()));
            assertFalse(active("request_types", hierarchy.requestTypeId()));

            catalogAdminService.activateRequestType(hierarchy.requestTypeId());
            catalogAdminService.deactivateSubcategory(hierarchy.subcategoryId());
            assertTrue(active("categories", hierarchy.categoryId()));
            assertFalse(active("subcategories", hierarchy.subcategoryId()));
            assertFalse(active("request_types", hierarchy.requestTypeId()));
        } finally {
            cleanup(hierarchy);
        }
    }

    private void runConcurrentParentDeactivationAndChildActivation(
            Hierarchy hierarchy,
            Runnable deactivation,
            Activation activation,
            boolean lockSubcategoryToo) throws Exception {
        CountDownLatch parentLocksHeld = new CountDownLatch(1);
        CountDownLatch childReachedCategoryLock = new CountDownLatch(1);
        CountDownLatch allowDeactivation = new CountDownLatch(1);
        CategoryRepository coordinatedCategories = mock(CategoryRepository.class, delegatesTo(categoryRepository));
        doAnswer(invocation -> {
            childReachedCategoryLock.countDown();
            return categoryRepository.findByIdForUpdate(invocation.getArgument(0));
        }).when(coordinatedCategories).findByIdForUpdate(anyLong());
        CatalogAdminService coordinatedService = new CatalogAdminService(
                coordinatedCategories, subcategoryRepository, requestTypeRepository, ticketRepository);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> deactivationFuture = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject(
                        "SELECT id FROM categories WHERE id = ? FOR UPDATE",
                        Long.class,
                        hierarchy.categoryId());
                if (lockSubcategoryToo) {
                    jdbcTemplate.queryForObject(
                            "SELECT id FROM subcategories WHERE id = ? FOR UPDATE",
                            Long.class,
                            hierarchy.subcategoryId());
                }
                parentLocksHeld.countDown();
                await(allowDeactivation);
                deactivation.run();
            }));

            assertTrue(parentLocksHeld.await(10, TimeUnit.SECONDS));
            Future<Exception> activationFuture = executor.submit(() -> {
                try {
                    transactions.executeWithoutResult(status -> activation.run(coordinatedService));
                    return null;
                } catch (Exception exception) {
                    return exception;
                }
            });

            assertTrue(childReachedCategoryLock.await(10, TimeUnit.SECONDS));
            allowDeactivation.countDown();
            deactivationFuture.get(10, TimeUnit.SECONDS);
            Exception activationOutcome = activationFuture.get(10, TimeUnit.SECONDS);
            assertInstanceOf(InvalidCatalogRequestException.class, activationOutcome);
        } finally {
            allowDeactivation.countDown();
            executor.shutdownNow();
        }
    }

    private Hierarchy createHierarchy(boolean subcategoryActive, boolean requestTypeActive) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Category category = new Category();
        category.setName("LockCategory" + suffix);
        category.setDescription("concurrency test");
        category = categoryRepository.save(category);

        Subcategory subcategory = new Subcategory();
        subcategory.setCategory(category);
        subcategory.setName("LockSubcategory" + suffix);
        subcategory.setDescription("concurrency test");
        subcategory.setActive(subcategoryActive);
        subcategory = subcategoryRepository.save(subcategory);

        RequestType requestType = new RequestType();
        requestType.setSubcategory(subcategory);
        requestType.setCode("LOCK_" + suffix);
        requestType.setName("Lock Request Type " + suffix);
        requestType.setDescription("concurrency test");
        requestType.setTicketType(TicketType.COMPLAINT);
        requestType.setResponsibleAreaId("M2");
        requestType.setMinimumPriority(Priority.LOW);
        requestType.setBaseRisk(Risk.LOW);
        requestType.setAffectedPopulationFactor(BigDecimal.ZERO);
        requestType.setActive(requestTypeActive);
        requestType = requestTypeRepository.save(requestType);

        return new Hierarchy(category.getId(), subcategory.getId(), requestType.getId());
    }

    private boolean active(String table, Long id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT active FROM " + table + " WHERE id = ?", Boolean.class, id));
    }

    private void cleanup(Hierarchy hierarchy) {
        jdbcTemplate.update("DELETE FROM request_types WHERE id = ?", hierarchy.requestTypeId());
        jdbcTemplate.update("DELETE FROM subcategories WHERE id = ?", hierarchy.subcategoryId());
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", hierarchy.categoryId());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timeout esperando coordinación de concurrencia");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando coordinación de concurrencia", exception);
        }
    }

    private record Hierarchy(Long categoryId, Long subcategoryId, Long requestTypeId) {
    }

    @FunctionalInterface
    private interface Activation {
        void run(CatalogAdminService service);
    }
}
