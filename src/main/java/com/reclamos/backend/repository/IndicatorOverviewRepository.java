package com.reclamos.backend.repository;

import com.reclamos.backend.dto.IndicatorOverviewFilter;
import com.reclamos.backend.dto.response.IndicatorOverviewResponse;
import com.reclamos.backend.dto.response.IndicatorOverviewResponse.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IndicatorOverviewRepository {
    private final NamedParameterJdbcTemplate jdbc;

    // Same source of truth as IndicatorRepositoryImpl: latest cycle per ticket/type.
    // LATERAL uses the existing (ticket_id, sla_type, cycle_number) index. No entity loading.
    private static final String BASE = """
            WITH facts AS (
              SELECT t.id, t.public_id, t.summary, t.current_status, t.current_priority,
                t.responsible_area_id, t.assigned_agent_id, t.created_at, t.updated_at,
                t.is_escalated, t.reopen_count, c.id AS category_id, c.name AS category_name,
                l.neighborhood_id, n.name AS neighborhood_name,
                (l.neighborhood_id IS NOT NULL OR NULLIF(trim(l.address_line),'') IS NOT NULL
                  OR NULLIF(trim(l.street),'') IS NOT NULL
                  OR (l.latitude IS NOT NULL AND l.longitude IS NOT NULL)) AS has_location,
                (t.main_ticket_id IS NULL AND t.current_status IN
                  ('REGISTERED','IN_REVIEW','ROUTED','IN_PROGRESS','PENDING_INFORMATION')) AS active,
                s.status AS sla_status, s.completed_at, s.paused_at, s.due_at,
                completed.status AS completed_sla_status,
                r.resolved_at,
                (t.main_ticket_id IS NULL AND t.current_status <> 'DUPLICATE'
                  AND s.id IS NOT NULL) AS has_sla
              FROM tickets t
              JOIN request_types rt ON rt.id=t.request_type_id
              JOIN subcategories sub ON sub.id=rt.subcategory_id
              JOIN categories c ON c.id=sub.category_id
              LEFT JOIN ticket_locations l ON l.ticket_id=t.id
              LEFT JOIN neighborhood n ON n.id=l.neighborhood_id
              LEFT JOIN LATERAL (SELECT id,status,completed_at,paused_at,due_at FROM ticket_sla
                WHERE ticket_id=t.id AND sla_type='RESOLUTION' ORDER BY cycle_number DESC LIMIT 1) s ON true
              LEFT JOIN LATERAL (SELECT status FROM ticket_sla
                WHERE ticket_id=t.id AND sla_type='RESOLUTION' AND completed_at IS NOT NULL
                ORDER BY cycle_number DESC LIMIT 1) completed ON true
              LEFT JOIN LATERAL (SELECT resolved_at FROM ticket_resolutions
                WHERE ticket_id=t.id ORDER BY resolution_number DESC LIMIT 1) r ON true
            ), classified AS (
              SELECT *,
                (active AND has_sla AND completed_at IS NULL AND sla_status='BREACHED') AS overdue,
                (active AND has_sla AND completed_at IS NULL AND paused_at IS NULL AND sla_status='NEAR_DUE') AS near_due
              FROM facts
            ), scoped AS (
              SELECT * FROM classified WHERE 1=1
            """;

    private record Scope(String sql, MapSqlParameterSource params, Instant start, Instant previousStart) { }

    private Scope scope(IndicatorOverviewFilter filter, Instant now) {
        Instant start = filter.days() == null ? null : now.minus(filter.days(), ChronoUnit.DAYS);
        Instant previousStart = start == null ? null : start.minus(filter.days(), ChronoUnit.DAYS);
        var params = new MapSqlParameterSource().addValue("now", Timestamp.from(now));
        StringBuilder sql = new StringBuilder(BASE);
        if (filter.categoryId() != null) {
            sql.append(" AND category_id=:category"); params.addValue("category", filter.categoryId());
        }
        if (filter.neighborhoodId() != null) {
            sql.append(" AND neighborhood_id=:neighborhood"); params.addValue("neighborhood", filter.neighborhoodId());
        }
        if (filter.priority() != null) {
            sql.append(" AND current_priority=:priority"); params.addValue("priority", filter.priority().name());
        }
        if (filter.status() != null) {
            sql.append(" AND current_status=:status"); params.addValue("status", filter.status().name());
        }
        if (filter.responsibleAreaId() != null && !filter.responsibleAreaId().isBlank()) {
            sql.append(" AND responsible_area_id=:area"); params.addValue("area", filter.responsibleAreaId());
        }
        if (Boolean.TRUE.equals(filter.withoutLocation())) sql.append(" AND NOT coalesce(has_location,false)");
        if (filter.sla() != null) {
            sql.append(switch (filter.sla()) {
                case "breached" -> " AND overdue";
                case "near_due" -> " AND near_due";
                case "on_track" -> " AND active AND has_sla AND completed_at IS NULL AND sla_status='RUNNING'";
                case "no_resolution_sla" -> " AND NOT has_sla";
                default -> "";
            });
        }
        sql.append(") , current_period AS (SELECT * FROM scoped WHERE created_at <= :now");
        if (start != null) {
            sql.append(" AND created_at >= :start");
            params.addValue("start", Timestamp.from(start)).addValue("previousStart", Timestamp.from(previousStart));
        }
        sql.append(") ");
        return new Scope(sql.toString(), params, start, previousStart);
    }

    public IndicatorOverviewResponse overview(IndicatorOverviewFilter filter, Instant now) {
        Scope scope = scope(filter, now);
        String base = scope.sql();
        var params = scope.params();
        Instant start = scope.start(), previousStart = scope.previousStart();
        String previous = start == null ? "0::bigint" :
                "(SELECT count(*) FROM scoped WHERE created_at >= :previousStart AND created_at < :start)";
        Counts counts = jdbc.queryForObject(base + """
                SELECT count(*) AS total, count(*) FILTER (WHERE active) AS active,
                  count(*) FILTER (WHERE active AND assigned_agent_id IS NULL) AS unassigned,
                  count(*) FILTER (WHERE overdue) AS overdue, count(*) FILTER (WHERE near_due) AS near_due,
                  count(*) FILTER (WHERE active AND (current_priority='CRITICAL' OR is_escalated OR overdue OR near_due)) AS urgent,
                  count(*) FILTER (WHERE current_status IN ('RESOLVED','CLOSED')) AS resolved,
                  count(*) FILTER (WHERE is_escalated) AS escalated, coalesce(sum(reopen_count),0) AS reopens,
                  count(*) FILTER (WHERE has_sla AND completed_sla_status IN ('MET','BREACHED')) AS measured,
                  count(*) FILTER (WHERE has_sla AND completed_sla_status='MET') AS met,
                  avg(extract(epoch FROM (resolved_at-created_at))/3600.0) FILTER
                    (WHERE current_status IN ('RESOLVED','CLOSED') AND resolved_at >= created_at) AS hours,
                """ + previous + " AS previous FROM current_period", params, (rs, row) -> new Counts(
                rs.getLong("total"), rs.getLong("active"), rs.getLong("unassigned"), rs.getLong("overdue"),
                rs.getLong("near_due"), rs.getLong("urgent"), rs.getLong("resolved"), rs.getLong("escalated"),
                rs.getLong("reopens"), rs.getLong("measured"), rs.getLong("met"),
                rs.getBigDecimal("hours") == null ? null : rs.getBigDecimal("hours").doubleValue(), rs.getLong("previous")));
        record Group(String dimension, Breakdown value) { }
        List<Group> groups = jdbc.query(base + """
                SELECT 'status' AS dimension,current_status AS key,current_status AS name,count(*) AS count
                  FROM current_period GROUP BY current_status
                UNION ALL SELECT 'category',category_id::text,category_name,count(*) FROM current_period GROUP BY category_id,category_name
                UNION ALL SELECT 'neighborhood',coalesce(neighborhood_id::text,''),
                  coalesce(neighborhood_name,'Sin barrio informado'),count(*) FROM current_period GROUP BY neighborhood_id,neighborhood_name
                UNION ALL SELECT 'area',responsible_area_id,responsible_area_id,count(*) FROM current_period GROUP BY responsible_area_id
                ORDER BY count DESC,key
                """, params, (rs, row) -> new Group(rs.getString("dimension"),
                new Breakdown(rs.getString("key"), rs.getString("name"), rs.getLong("count"))));
        List<Trend> trend = List.of();
        List<AttentionTicket> attention = List.of();
        if (start != null) {
            int buckets = filter.days() == 7 ? 7 : filter.days() == 30 ? 10 : 12;
            params.addValue("buckets", buckets).addValue("seconds", filter.days() * 86400.0 / buckets);
            trend = jdbc.query(base + """
                    , buckets AS (SELECT i, cast(:start as timestamptz) + (i * :seconds) * interval '1 second' AS bucket_start,
                      cast(:start as timestamptz) + ((i+1) * :seconds) * interval '1 second' AS bucket_end
                      FROM generate_series(0,:buckets-1) i), events AS (
                      SELECT created_at AS at,'created' AS kind FROM scoped WHERE created_at >= :start AND created_at <= :now
                      UNION ALL SELECT resolved_at,'resolved' FROM scoped WHERE current_status IN ('RESOLVED','CLOSED')
                        AND resolved_at >= :start AND resolved_at <= :now)
                    SELECT b.bucket_start,count(*) FILTER (WHERE e.kind='created') AS created,
                      count(*) FILTER (WHERE e.kind='resolved') AS resolved FROM buckets b
                      LEFT JOIN events e ON e.at >= b.bucket_start AND (e.at < b.bucket_end OR (b.i=:buckets-1 AND e.at=:now))
                      GROUP BY b.i,b.bucket_start ORDER BY b.i
                    """, params, (rs, row) -> new Trend(rs.getTimestamp("bucket_start").toInstant(),
                    rs.getLong("created"), rs.getLong("resolved")));
        } else {
            attention = jdbc.query(base + """
                    SELECT * FROM current_period WHERE active AND
                      (current_priority='CRITICAL' OR is_escalated OR overdue OR near_due)
                    ORDER BY overdue DESC,near_due DESC,is_escalated DESC,updated_at DESC,id LIMIT 6
                    """, params, (rs, row) -> new AttentionTicket(rs.getObject("id", UUID.class),
                    rs.getString("public_id"), rs.getString("summary"), rs.getString("current_status"),
                    rs.getString("current_priority"), rs.getBoolean("is_escalated"), rs.getBoolean("overdue"),
                    rs.getBoolean("near_due"), rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()));
        }
        return new IndicatorOverviewResponse(now, start, previousStart, counts,
                groups.stream().filter(g -> g.dimension().equals("status")).map(Group::value).toList(),
                groups.stream().filter(g -> g.dimension().equals("category")).map(Group::value).toList(),
                groups.stream().filter(g -> g.dimension().equals("neighborhood")).map(Group::value).toList(),
                groups.stream().filter(g -> g.dimension().equals("area")).map(Group::value).toList(), trend, attention);
    }

    /** The service transaction keeps PostgreSQL's cursor open until the response has been written. */
    public void export(IndicatorOverviewFilter filter, Instant now, OutputStream output) {
        Scope scope = scope(filter, now);
        jdbc.execute(scope.sql() + "SELECT public_id,created_at,current_status,responsible_area_id,"
                + "coalesce(neighborhood_name,'Sin ubicación informada') AS location "
                + "FROM current_period ORDER BY created_at DESC,id", scope.params(), statement -> {
            statement.setFetchSize(200);
            try (var rows = statement.executeQuery()) {
                var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                writer.write("\uFEFF\"Ticket\",\"Creado\",\"Estado\",\"Área\",\"Ubicación\"\r\n");
                while (rows.next()) {
                    writer.write(csv(rows.getString("public_id")) + ","
                            + csv(rows.getTimestamp("created_at").toInstant().toString()) + ","
                            + csv(statusLabel(rows.getString("current_status"))) + ","
                            + csv(rows.getString("responsible_area_id")) + "," + csv(rows.getString("location")) + "\r\n");
                }
                writer.flush();
                return null;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        String stripped = text.stripLeading();
        if (!stripped.isEmpty() && ("=+-@".indexOf(stripped.charAt(0)) >= 0
                || text.charAt(0) == '\t' || text.charAt(0) == '\r' || text.charAt(0) == '\n')) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "REGISTERED" -> "Registrado";
            case "IN_REVIEW" -> "En revisión";
            case "ROUTED" -> "Derivado";
            case "IN_PROGRESS" -> "En gestión";
            case "PENDING_INFORMATION" -> "Pendiente de información";
            case "RESOLVED" -> "Resuelto";
            case "CLOSED" -> "Cerrado";
            case "DUPLICATE" -> "Duplicado";
            case "CANCELLED" -> "Cancelado";
            default -> status;
        };
    }
}
