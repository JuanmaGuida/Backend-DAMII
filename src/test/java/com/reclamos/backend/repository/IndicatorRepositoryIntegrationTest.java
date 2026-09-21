package com.reclamos.backend.repository;

import com.reclamos.backend.dto.IndicatorFilter;
import com.reclamos.backend.dto.response.SlaIndicatorResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.ModuleRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
@ActiveProfiles("dev")
@Transactional
class IndicatorRepositoryIntegrationTest {

    @Autowired
    IndicatorRepository indicators;

    @Autowired
    CategoryRepository categories;

    @Autowired
    SubcategoryRepository subcategories;

    @Autowired
    RequestTypeRepository requestTypes;

    @Autowired
    TicketRepository tickets;

    @Autowired
    TicketLocationRepository locations;

    @Autowired
    NeighborhoodRepository neighborhoods;

    @Autowired
    TicketSlaRepository slas;

    @Autowired
    SlaPolicyRepository policies;

    @Autowired
    EntityManager entityManager;

    private static final IndicatorFilter NO_FILTERS =
            new IndicatorFilter(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

    @Test
    void databaseGroupsEveryDimensionAfterFiltersAndDoesNotMultiplySlaJoins() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Category a = category("A-" + suffix);
        Category b = category("B-" + suffix);

        RequestType typeA = requestType(a, "A-" + suffix);
        RequestType typeB = requestType(b, "B-" + suffix);

        Neighborhood north = neighborhood("Norte-" + suffix);
        Neighborhood center = neighborhood("Centro-" + suffix);

        Ticket matching = ticket(
                typeA,
                Priority.HIGH,
                "M6",
                TicketStatus.IN_PROGRESS,
                suffix + "-1"
        );

        locate(matching, north);

        sla(
                matching,
                SlaType.FIRST_RESPONSE,
                1,
                SlaStatus.MET
        );

        sla(
                matching,
                SlaType.RESOLUTION,
                1,
                SlaStatus.BREACHED
        );

        sla(
                matching,
                SlaType.RESOLUTION,
                2,
                SlaStatus.RUNNING
        );

        Ticket secondA = ticket(
                typeA,
                Priority.HIGH,
                "M6",
                TicketStatus.IN_PROGRESS,
                suffix + "-2"
        );

        locate(secondA, north);

        sla(
                secondA,
                SlaType.RESOLUTION,
                1,
                SlaStatus.BREACHED
        );

        Ticket thirdA = ticket(
                typeA,
                Priority.LOW,
                "M2",
                TicketStatus.REGISTERED,
                suffix + "-3"
        );

        locate(thirdA, center);

        Ticket firstB = ticket(
                typeB,
                Priority.MEDIUM,
                "M2",
                TicketStatus.REGISTERED,
                suffix + "-4"
        );

        Ticket secondB = ticket(
                typeB,
                Priority.MEDIUM,
                "M2",
                TicketStatus.REGISTERED,
                suffix + "-5"
        );

        sla(
                firstB,
                SlaType.FIRST_RESPONSE,
                1,
                SlaStatus.NEAR_DUE
        );

        sla(
                secondB,
                SlaType.RESOLUTION,
                1,
                SlaStatus.STOPPED
        );

        entityManager.flush();

        assertEquals(
                3,
                countCategory(a.getId(), NO_FILTERS)
        );

        assertEquals(
                2,
                countCategory(b.getId(), NO_FILTERS)
        );

        assertEquals(
                2,
                countNeighborhood(north.getId())
        );

        assertEquals(
                1,
                countNeighborhood(center.getId())
        );

        IndicatorFilter categoryA =
                new IndicatorFilter(
                        a.getId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertEquals(
                2,
                indicators.aggregatePriorities(categoryA)
                        .stream()
                        .filter(x -> x.getPriority() == Priority.HIGH)
                        .findFirst()
                        .orElseThrow()
                        .getCount()
        );

        assertEquals(
                1,
                indicators.aggregateAreas(categoryA)
                        .stream()
                        .filter(x -> x.getResponsibleAreaId().equals("M2"))
                        .findFirst()
                        .orElseThrow()
                        .getCount()
        );

        IndicatorFilter combined =
                new IndicatorFilter(
                        a.getId(),
                        north.getId(),
                        Priority.HIGH,
                        "M6",
                        TicketStatus.IN_PROGRESS,
                        SlaType.RESOLUTION,
                        SlaStatus.BREACHED
                );

        assertEquals(
                1,
                countCategory(a.getId(), combined)
        );

        assertEquals(
                1,
                indicators.aggregateAreas(combined)
                        .getFirst()
                        .getCount()
        );

        assertEquals(
                1,
                indicators.aggregatePriorities(combined)
                        .getFirst()
                        .getCount()
        );

        assertEquals(
                1,
                slaCount(
                        categoryA,
                        SlaType.RESOLUTION,
                        SlaStatus.RUNNING
                )
        );

        assertEquals(
                1,
                slaCount(
                        categoryA,
                        SlaType.RESOLUTION,
                        SlaStatus.BREACHED
                )
        );

        assertEquals(
                1,
                slaCount(
                        categoryA,
                        SlaType.FIRST_RESPONSE,
                        SlaStatus.MET
                )
        );

        IndicatorFilter categoryB =
                new IndicatorFilter(
                        b.getId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        assertEquals(
                1,
                slaCount(
                        categoryB,
                        SlaType.FIRST_RESPONSE,
                        SlaStatus.NEAR_DUE
                )
        );

        assertEquals(
                1,
                slaCount(
                        categoryB,
                        SlaType.RESOLUTION,
                        SlaStatus.STOPPED
                )
        );

        assertFalse(
                indicators.aggregateSla(categoryA)
                        .stream()
                        .anyMatch(
                                x -> x.getSlaType() == SlaType.RESOLUTION
                                        && x.getStatus() == SlaStatus.BREACHED
                                        && x.getCount() > 1
                        )
        );

        assertEquals(
                1,
                countCategory(
                        a.getId(),
                        new IndicatorFilter(
                                null,
                                null,
                                Priority.LOW,
                                null,
                                null,
                                null,
                                null
                        )
                )
        );

        assertEquals(
                2,
                countCategory(
                        a.getId(),
                        new IndicatorFilter(
                                null,
                                null,
                                Priority.HIGH,
                                null,
                                null,
                                null,
                                null
                        )
                )
        );
    }

    private long countCategory(
            Long id,
            IndicatorFilter filter
    ) {
        return indicators.aggregateCategories(filter)
                .stream()
                .filter(x -> x.getCategoryId().equals(id))
                .findFirst()
                .orElseThrow()
                .getCount();
    }

    private long countNeighborhood(UUID id) {
        return indicators.aggregateNeighborhoods(NO_FILTERS)
                .stream()
                .filter(x -> x.getNeighborhoodId().equals(id))
                .findFirst()
                .orElseThrow()
                .getCount();
    }

    private long slaCount(
            IndicatorFilter filter,
            SlaType type,
            SlaStatus status
    ) {
        return indicators.aggregateSla(filter)
                .stream()
                .filter(
                        x -> x.getSlaType() == type
                                && x.getStatus() == status
                )
                .mapToLong(SlaIndicatorResponse::getCount)
                .sum();
    }

    private Category category(String name) {
        Category value = new Category();

        value.setName(name);
        value.setDescription(name);

        return categories.save(value);
    }

    private RequestType requestType(
            Category category,
            String code
    ) {
        Subcategory sub = new Subcategory();

        sub.setCategory(category);
        sub.setName(code);
        sub.setDescription(code);

        subcategories.save(sub);

        RequestType type = new RequestType();

        type.setSubcategory(sub);
        type.setCode(code);
        type.setName(code);
        type.setDescription(code);
        type.setTicketType(TicketType.COMPLAINT);
        type.setResponsibleAreaId("M2");
        type.setMinimumPriority(Priority.LOW);
        type.setBaseRisk(Risk.LOW);
        type.setAffectedPopulationFactor(BigDecimal.ZERO);

        return requestTypes.save(type);
    }

    private Neighborhood neighborhood(String name) {
        Neighborhood value = new Neighborhood();

        value.setName(name);
        value.setPopulation(1);

        return neighborhoods.save(value);
    }

    private Ticket ticket(
            RequestType type,
            Priority priority,
            String area,
            TicketStatus status,
            String suffix
    ) {
        UUID citizenId = UUID.randomUUID();

        ModuleUser citizen = new ModuleUser();
        citizen.setCitizenId(citizenId);
        citizen.setFirstName("Test");
        citizen.setLastName("Citizen");
        citizen.setRole(ModuleRole.CITIZEN);
        citizen.setActive(true);
        citizen.setLastSyncedAt(Instant.now());

        entityManager.persist(citizen);

        // Importante porque Ticket referencia citizen_id mediante UUID,
        // no mediante una relación JPA con ModuleUser.
        entityManager.flush();

        Ticket value = new Ticket();

        value.setPublicId("IND-" + suffix);
        value.setTrackingCodeHash("hash-" + suffix);

        value.setAnonymous(false);
        value.setCitizenId(citizenId);

        value.setRequestType(type);
        value.setTicketType(type.getTicketType());
        value.setResponsibleAreaId(area);

        value.setSummary("summary");
        value.setDescription("description");

        value.setCurrentStatus(status);
        value.setCurrentPriority(priority);
        value.setStatusChangedAt(Instant.now());

        return tickets.save(value);
    }

    private void locate(
            Ticket ticket,
            Neighborhood neighborhood
    ) {
        TicketLocation value = new TicketLocation();

        value.setTicket(ticket);
        value.setNeighborhood(neighborhood);

        locations.save(value);
    }

    private void sla(
            Ticket ticket,
            SlaType type,
            int cycle,
            SlaStatus status
    ) {
        TicketSla value = new TicketSla();

        value.setTicket(ticket);
        value.setSlaType(type);
        value.setCycleNumber(cycle);

        value.setPolicy(
                policies.findByPriorityAndSlaType(Priority.HIGH, type)
                        .orElseThrow()
        );

        value.setStartedAt(
                Instant.parse("2026-01-01T00:00:00Z")
        );

        value.setNearDueAt(
                Instant.parse("2026-01-01T01:00:00Z")
        );

        value.setDueAt(
                Instant.parse("2026-01-01T02:00:00Z")
        );

        value.setStatus(status);

        if (status == SlaStatus.MET) {
            value.setCompletedAt(
                    Instant.parse("2026-01-01T01:30:00Z")
            );
        } else if (status == SlaStatus.BREACHED) {
            value.setCompletedAt(
                    Instant.parse("2026-01-01T03:00:00Z")
            );
        } else if (status == SlaStatus.STOPPED) {
            value.setCompletedAt(
                    Instant.parse("2026-01-01T01:45:00Z")
            );
        }

        slas.save(value);
    }
}