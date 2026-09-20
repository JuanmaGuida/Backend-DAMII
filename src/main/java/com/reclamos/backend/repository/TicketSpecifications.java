package com.reclamos.backend.repository;

import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.entity.*;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtros combinables para la bandeja de tickets (Story 3.1).
 * <p>
 * Se construye vía Criteria API para no tener que agregar una relación inversa
 * Ticket -&gt; TicketLocation en la entidad Ticket (que está siendo tocada en paralelo
 * por el alta de tickets) y así evitar conflictos de merge innecesarios.
 */
public final class TicketSpecifications {

    private TicketSpecifications() {
    }

    public static Specification<Ticket> build(TicketFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("currentStatus"), filter.status()));
            }
            if (filter.priority() != null) {
                predicates.add(cb.equal(root.get("currentPriority"), filter.priority()));
            }
            if (filter.responsibleAreaId() != null && !filter.responsibleAreaId().isBlank()) {
                predicates.add(cb.equal(root.get("responsibleAreaId"), filter.responsibleAreaId()));
            }
            if (filter.categoryId() != null) {
                Join<Ticket, RequestType> requestTypeJoin = root.join("requestType");
                Join<RequestType, Subcategory> subcategoryJoin = requestTypeJoin.join("subcategory");
                predicates.add(cb.equal(subcategoryJoin.get("category").get("id"), filter.categoryId()));
            }
            if (filter.neighborhoodId() != null && query != null) {
                Subquery<Long> locationSubquery = query.subquery(Long.class);
                Root<TicketLocation> locationRoot = locationSubquery.from(TicketLocation.class);
                locationSubquery.select(locationRoot.get("id"))
                        .where(
                                cb.equal(locationRoot.get("ticket"), root),
                                cb.equal(locationRoot.get("neighborhood").get("id"), filter.neighborhoodId())
                        );
                predicates.add(cb.exists(locationSubquery));
            }

            if (filter.labelIds() != null && !filter.labelIds().isEmpty() && query != null) {
                Subquery<Integer> labels = query.subquery(Integer.class);
                Root<TicketLabel> assignment = labels.from(TicketLabel.class);
                labels.select(cb.literal(1)).where(
                        cb.equal(assignment.get("ticket"), root),
                        assignment.get("label").get("id").in(filter.labelIds()));
                predicates.add(cb.exists(labels));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
