package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Label;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
import org.springframework.data.jpa.repository.Query;

public interface LabelRepository extends JpaRepository<Label, UUID> {

    boolean existsByCodeIgnoreCase(String code);
    List<Label> findAllByOrderByNameAscIdAsc();
    @Query("""
            select l.id as id,
                   l.code as code,
                   l.name as name,
                   l.description as description,
                   l.active as active,
                   count(tl) as ticketCount
            from Label l
            left join TicketLabel tl on tl.label = l
            group by l.id, l.code, l.name, l.description, l.active
            order by l.name asc, l.id asc
            """)
    List<LabelWithTicketCount> findAllWithTicketCount();
}