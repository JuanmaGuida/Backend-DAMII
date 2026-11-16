package com.reclamos.backend.repository;

import com.reclamos.backend.dto.response.AgentDirectoryEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AgentDirectoryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    // Staff lives in module_users with a local role. Workload comes from tickets.assigned_agent_id;
    // the SLA and resolution facts use the same "latest cycle / latest resolution" rule as indicators.
    private static final String DIRECTORY = """
            SELECT u.actor_id, u.first_name, u.last_name, u.email, u.role, u.area_id, u.active,
              count(t.id) FILTER (WHERE t.main_ticket_id IS NULL AND t.current_status IN
                ('IN_REVIEW','ROUTED','IN_PROGRESS','PENDING_INFORMATION')) AS active_tickets,
              count(t.id) FILTER (WHERE r.resolved_at >= :monthStart AND r.resolved_at < :monthEnd)
                AS resolved_this_month,
              count(t.id) FILTER (WHERE s.status = 'MET') AS sla_met,
              count(t.id) FILTER (WHERE s.status = 'BREACHED') AS sla_breached
            FROM module_users u
            LEFT JOIN tickets t ON t.assigned_agent_id = u.id
            LEFT JOIN LATERAL (SELECT resolved_at FROM ticket_resolutions
              WHERE ticket_id = t.id ORDER BY resolution_number DESC LIMIT 1) r ON true
            LEFT JOIN LATERAL (SELECT status FROM ticket_sla
              WHERE ticket_id = t.id AND sla_type = 'RESOLUTION' AND completed_at IS NOT NULL
              ORDER BY cycle_number DESC LIMIT 1) s ON true
            WHERE u.role IN ('AGENT','AREA_RESPONSIBLE','ADMIN')
            GROUP BY u.id
            ORDER BY u.active DESC, lower(u.last_name), lower(u.first_name), u.id
            """;

    public List<AgentDirectoryEntry> list(Instant monthStart, Instant monthEnd) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("monthStart", Timestamp.from(monthStart))
                .addValue("monthEnd", Timestamp.from(monthEnd));
        return jdbc.query(DIRECTORY, parameters, (rs, row) -> new AgentDirectoryEntry(
                rs.getObject("actor_id", UUID.class),
                rs.getString("first_name"),
                rs.getString("last_name"),
                rs.getString("email"),
                rs.getString("role"),
                rs.getString("area_id"),
                rs.getBoolean("active"),
                rs.getLong("active_tickets"),
                rs.getLong("resolved_this_month"),
                rs.getLong("sla_met"),
                rs.getLong("sla_breached")));
    }
}
