package com.reclamos.backend.repository;

import com.reclamos.backend.dto.IndicatorFilter;
import com.reclamos.backend.dto.response.*;
import com.reclamos.backend.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class IndicatorRepositoryImpl implements IndicatorRepository {
    private final EntityManager entityManager;

    @Override
    public List<CategoryIndicatorResponse> aggregateCategories(IndicatorFilter filter) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CategoryIndicatorResponse> query = cb.createQuery(CategoryIndicatorResponse.class);
        Root<Ticket> ticket = query.from(Ticket.class);
        Join<Ticket, RequestType> type = ticket.join("requestType");
        Join<RequestType, Subcategory> subcategory = type.join("subcategory");
        Join<Subcategory, Category> category = subcategory.join("category");
        query.select(cb.construct(CategoryIndicatorResponse.class, category.get("id"), category.get("name"),
                        cb.countDistinct(ticket.get("id"))))
                .where(predicates(filter, query, cb, ticket))
                .groupBy(category.get("id"), category.get("name"))
                .orderBy(cb.asc(category.get("name")));
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<NeighborhoodIndicatorResponse> aggregateNeighborhoods(IndicatorFilter filter) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<NeighborhoodIndicatorResponse> query = cb.createQuery(NeighborhoodIndicatorResponse.class);
        Root<Ticket> ticket = query.from(Ticket.class);
        Root<TicketLocation> location = query.from(TicketLocation.class);
        Join<TicketLocation, Neighborhood> neighborhood = location.join("neighborhood");
        List<Predicate> restrictions = new ArrayList<>(List.of(predicates(filter, query, cb, ticket)));
        restrictions.add(cb.equal(location.get("ticket"), ticket));
        query.select(cb.construct(NeighborhoodIndicatorResponse.class, neighborhood.get("id"), neighborhood.get("name"),
                        cb.countDistinct(ticket.get("id"))))
                .where(cb.and(restrictions.toArray(Predicate[]::new)))
                .groupBy(neighborhood.get("id"), neighborhood.get("name"))
                .orderBy(cb.asc(neighborhood.get("name")));
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<PriorityIndicatorResponse> aggregatePriorities(IndicatorFilter filter) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<PriorityIndicatorResponse> query = cb.createQuery(PriorityIndicatorResponse.class);
        Root<Ticket> ticket = query.from(Ticket.class);
        query.select(cb.construct(PriorityIndicatorResponse.class, ticket.get("currentPriority"),
                        cb.countDistinct(ticket.get("id"))))
                .where(predicates(filter, query, cb, ticket)).groupBy(ticket.get("currentPriority"));
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<AreaIndicatorResponse> aggregateAreas(IndicatorFilter filter) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<AreaIndicatorResponse> query = cb.createQuery(AreaIndicatorResponse.class);
        Root<Ticket> ticket = query.from(Ticket.class);
        query.select(cb.construct(AreaIndicatorResponse.class, ticket.get("responsibleAreaId"),
                        cb.countDistinct(ticket.get("id"))))
                .where(predicates(filter, query, cb, ticket)).groupBy(ticket.get("responsibleAreaId"))
                .orderBy(cb.asc(ticket.get("responsibleAreaId")));
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<SlaIndicatorResponse> aggregateSla(IndicatorFilter filter) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<SlaIndicatorResponse> query = cb.createQuery(SlaIndicatorResponse.class);
        Root<TicketSla> sla = query.from(TicketSla.class);
        Join<TicketSla, Ticket> ticket = sla.join("ticket");
        Subquery<Integer> latestCycle = query.subquery(Integer.class);
        Root<TicketSla> candidate = latestCycle.from(TicketSla.class);
        latestCycle.select(cb.max(candidate.<Integer>get("cycleNumber"))).where(
                cb.equal(candidate.get("ticket"), ticket), cb.equal(candidate.get("slaType"), sla.get("slaType")));
        List<Predicate> restrictions = new ArrayList<>(List.of(predicates(filter, query, cb, ticket)));
        restrictions.add(cb.equal(sla.get("cycleNumber"), latestCycle));
        if (filter.getSlaType() != null) restrictions.add(cb.equal(sla.get("slaType"), filter.getSlaType()));
        if (filter.getSlaStatus() != null) restrictions.add(cb.equal(sla.get("status"), filter.getSlaStatus()));
        query.select(cb.construct(SlaIndicatorResponse.class, sla.get("slaType"), sla.get("status"),
                        cb.countDistinct(ticket.get("id"))))
                .where(cb.and(restrictions.toArray(Predicate[]::new)))
                .groupBy(sla.get("slaType"), sla.get("status"));
        return entityManager.createQuery(query).getResultList();
    }

    private Predicate predicates(IndicatorFilter filter, CriteriaQuery<?> query, CriteriaBuilder cb,
                                 From<?, Ticket> ticket) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.getCategoryId() != null) {
            predicates.add(cb.equal(ticket.get("requestType").get("subcategory").get("category").get("id"),
                    filter.getCategoryId()));
        }
        if (filter.getPriority() != null) predicates.add(cb.equal(ticket.get("currentPriority"), filter.getPriority()));
        if (filter.getStatus() != null) predicates.add(cb.equal(ticket.get("currentStatus"), filter.getStatus()));
        if (filter.getResponsibleAreaId() != null && !filter.getResponsibleAreaId().isBlank()) {
            predicates.add(cb.equal(ticket.get("responsibleAreaId"), filter.getResponsibleAreaId()));
        }
        if (filter.getNeighborhoodId() != null) {
            Subquery<Integer> location = query.subquery(Integer.class);
            Root<TicketLocation> root = location.from(TicketLocation.class);
            location.select(cb.literal(1)).where(cb.equal(root.get("ticket"), ticket),
                    cb.equal(root.get("neighborhood").get("id"), filter.getNeighborhoodId()));
            predicates.add(cb.exists(location));
        }
        if (filter.getSlaType() != null || filter.getSlaStatus() != null) {
            Subquery<Integer> matchingSla = query.subquery(Integer.class);
            Root<TicketSla> sla = matchingSla.from(TicketSla.class);
            Subquery<Integer> latestCycle = matchingSla.subquery(Integer.class);
            Root<TicketSla> candidate = latestCycle.from(TicketSla.class);
            latestCycle.select(cb.max(candidate.<Integer>get("cycleNumber"))).where(
                    cb.equal(candidate.get("ticket"), ticket),
                    cb.equal(candidate.get("slaType"), sla.get("slaType")));
            List<Predicate> slaPredicates = new ArrayList<>();
            slaPredicates.add(cb.equal(sla.get("ticket"), ticket));
            slaPredicates.add(cb.equal(sla.get("cycleNumber"), latestCycle));
            if (filter.getSlaType() != null) slaPredicates.add(cb.equal(sla.get("slaType"), filter.getSlaType()));
            if (filter.getSlaStatus() != null) slaPredicates.add(cb.equal(sla.get("status"), filter.getSlaStatus()));
            matchingSla.select(cb.literal(1)).where(slaPredicates.toArray(Predicate[]::new));
            predicates.add(cb.exists(matchingSla));
        }
        return cb.and(predicates.toArray(Predicate[]::new));
    }
}