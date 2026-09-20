package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Risk;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketLocation;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.identity.ModuleRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class DuplicateCandidateRepositoryIntegrationTest {
    private static final Instant SOURCE_CREATED_AT = Instant.parse("2026-09-18T12:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final Set<TicketStatus> ELIGIBLE_STATUSES = Set.of(
            TicketStatus.REGISTERED, TicketStatus.IN_REVIEW, TicketStatus.ROUTED,
            TicketStatus.IN_PROGRESS, TicketStatus.PENDING_INFORMATION);

    @Autowired private CategoryRepository categoryRepository;
    @Autowired private SubcategoryRepository subcategoryRepository;
    @Autowired private RequestTypeRepository requestTypeRepository;
    @Autowired private ModuleUserRepository moduleUserRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private TicketLocationRepository locationRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void realQueryNavigatesCategoryAndAppliesEveryDatabasePreselectionFilter() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID citizenId = moduleUser(suffix).getCitizenId();
        Category category = category("duplicate-category-" + suffix);
        Category otherCategory = category("other-category-" + suffix);
        Subcategory sourceSubcategory = subcategory(category, "source-subcategory-" + suffix);
        Subcategory siblingSubcategory = subcategory(category, "sibling-subcategory-" + suffix);
        Subcategory otherSubcategory = subcategory(otherCategory, "other-subcategory-" + suffix);
        RequestType sourceType = requestType(sourceSubcategory, "SOURCE-" + suffix);
        RequestType sameSubcategoryType = requestType(sourceSubcategory, "SAME-SUBCATEGORY-" + suffix);
        RequestType siblingSubcategoryType = requestType(siblingSubcategory, "SIBLING-SUBCATEGORY-" + suffix);
        RequestType otherCategoryType = requestType(otherSubcategory, "OTHER-CATEGORY-" + suffix);

        Ticket source = ticket(sourceType, TicketStatus.IN_REVIEW, "source-" + suffix, citizenId);
        Ticket differentRequestType = ticket(
                sameSubcategoryType, TicketStatus.REGISTERED, "different-request-type-" + suffix, citizenId);
        Ticket differentSubcategory = ticket(
                siblingSubcategoryType, TicketStatus.IN_PROGRESS, "different-subcategory-" + suffix, citizenId);
        Ticket differentCategory = ticket(
                otherCategoryType, TicketStatus.REGISTERED, "different-category-" + suffix, citizenId);
        Ticket outsideWindow = ticket(
                sourceType, TicketStatus.REGISTERED, "outside-window-" + suffix, citizenId);
        Ticket withoutCoordinates = ticket(
                sourceType, TicketStatus.REGISTERED, "without-coordinates-" + suffix, citizenId);
        List<Ticket> invalidStatuses = List.of(
                ticket(sourceType, TicketStatus.DUPLICATE, "duplicate-" + suffix, citizenId),
                ticket(sourceType, TicketStatus.RESOLVED, "resolved-" + suffix, citizenId),
                ticket(sourceType, TicketStatus.CLOSED, "closed-" + suffix, citizenId),
                ticket(sourceType, TicketStatus.CANCELLED, "cancelled-" + suffix, citizenId));
        ticketRepository.saveAll(invalidStatuses);

        location(source, "-34.603722", "-58.381592");
        location(differentRequestType, "-34.603800", "-58.381592");
        location(differentSubcategory, "-34.603900", "-58.381592");
        location(differentCategory, "-34.603800", "-58.381592");
        location(outsideWindow, "-34.603800", "-58.381592");
        location(withoutCoordinates, null, null);
        invalidStatuses.forEach(ticket -> location(ticket, "-34.603800", "-58.381592"));
        locationRepository.flush();

        setCreatedAt(source, SOURCE_CREATED_AT);
        setCreatedAt(differentRequestType, SOURCE_CREATED_AT.minus(WINDOW));
        setCreatedAt(differentSubcategory, SOURCE_CREATED_AT.plus(WINDOW));
        setCreatedAt(differentCategory, SOURCE_CREATED_AT);
        setCreatedAt(outsideWindow, SOURCE_CREATED_AT.plus(WINDOW).plusSeconds(1));
        setCreatedAt(withoutCoordinates, SOURCE_CREATED_AT);
        invalidStatuses.forEach(ticket -> setCreatedAt(ticket, SOURCE_CREATED_AT));
        entityManager.flush();
        entityManager.clear();

        List<UUID> candidateIds = locationRepository.findDuplicateCandidates(
                        source.getId(), category.getId(), SOURCE_CREATED_AT.minus(WINDOW),
                        SOURCE_CREATED_AT.plus(WINDOW), ELIGIBLE_STATUSES).stream()
                .map(location -> location.getTicket().getId())
                .toList();

        assertEquals(Set.of(differentRequestType.getId(), differentSubcategory.getId()),
                Set.copyOf(candidateIds));
        assertEquals(2, candidateIds.size(), "la query no debe duplicar resultados por sus fetch joins");
    }

    private Category category(String name) {
        Category category = new Category();
        category.setName(name);
        category.setDescription(name);
        return categoryRepository.save(category);
    }

    private Subcategory subcategory(Category category, String name) {
        Subcategory subcategory = new Subcategory();
        subcategory.setCategory(category);
        subcategory.setName(name);
        subcategory.setDescription(name);
        return subcategoryRepository.save(subcategory);
    }

    private RequestType requestType(Subcategory subcategory, String code) {
        RequestType requestType = new RequestType();
        requestType.setSubcategory(subcategory);
        requestType.setCode(code);
        requestType.setName(code);
        requestType.setDescription(code);
        requestType.setTicketType(TicketType.COMPLAINT);
        requestType.setResponsibleAreaId("M2");
        requestType.setMinimumPriority(Priority.LOW);
        requestType.setBaseRisk(Risk.LOW);
        requestType.setAffectedPopulationFactor(BigDecimal.ZERO);
        return requestTypeRepository.save(requestType);
    }

    private ModuleUser moduleUser(String suffix) {
        ModuleUser moduleUser = new ModuleUser();
        moduleUser.setCitizenId(UUID.randomUUID());
        moduleUser.setFirstName("Duplicate");
        moduleUser.setLastName("Candidate " + suffix);
        moduleUser.setEmail("duplicate-candidate-" + suffix + "@example.test");
        moduleUser.setRole(ModuleRole.CITIZEN);
        moduleUser.setLastSyncedAt(SOURCE_CREATED_AT);
        return moduleUserRepository.save(moduleUser);
    }

    private Ticket ticket(RequestType requestType, TicketStatus status, String suffix, UUID citizenId) {
        Ticket ticket = new Ticket();
        ticket.setPublicId("TEST-" + suffix);
        ticket.setTrackingCodeHash("hash-" + suffix);
        ticket.setCitizenId(citizenId);
        ticket.setAnonymous(false);
        ticket.setRequestType(requestType);
        ticket.setTicketType(requestType.getTicketType());
        ticket.setResponsibleAreaId(requestType.getResponsibleAreaId());
        ticket.setSummary("summary");
        ticket.setDescription("description");
        ticket.setCurrentStatus(status);
        ticket.setCurrentPriority(Priority.LOW);
        ticket.setStatusChangedAt(SOURCE_CREATED_AT);
        return ticketRepository.save(ticket);
    }

    private void location(Ticket ticket, String latitude, String longitude) {
        TicketLocation location = new TicketLocation();
        location.setTicket(ticket);
        location.setLatitude(latitude == null ? null : new BigDecimal(latitude));
        location.setLongitude(longitude == null ? null : new BigDecimal(longitude));
        locationRepository.save(location);
    }

    private void setCreatedAt(Ticket ticket, Instant createdAt) {
        entityManager.createNativeQuery("update tickets set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", ticket.getId())
                .executeUpdate();
    }
}