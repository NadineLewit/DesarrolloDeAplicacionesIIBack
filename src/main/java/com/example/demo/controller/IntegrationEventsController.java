package com.example.demo.controller;

import com.example.demo.infrastructure.integration.EventCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import com.example.demo.exception.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

/** Read-only view of persisted transport activity; payloads are intentionally excluded. */
@RestController
@RequestMapping("/api/public-works/integration-events")
public class IntegrationEventsController {
    private final JdbcTemplate jdbc;
    private final EventCodec codec;
    public IntegrationEventsController(JdbcTemplate jdbc, EventCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public record EventView(String id, String direction, String eventType, String sourceModule,
            String sourceRequestId, String status, OffsetDateTime receivedAt,
            OffsetDateTime processedAt, String error) {}
    public record EventPage(List<EventView> content, int page, int size, long totalElements,
            int totalPages, boolean first, boolean last) {}

    @GetMapping
    public EventPage list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ALL") String direction) {
        if (page < 0 || size < 1 || size > 100 || page > 1000000
                || !List.of("ALL", "INCOMING", "OUTGOING").contains(direction)) {
            throw new InvalidRequestException("Paginacion o direccion invalida");
        }
        String incoming = "SELECT event_id AS id, 'INCOMING' AS direction, envelope, status, received_at AS activity_at, processed_at AS completed_at, error FROM event_inbox";
        String outgoing = "SELECT event_id AS id, 'OUTGOING' AS direction, envelope, status, created_at AS activity_at, published_at AS completed_at, last_error AS error FROM event_outbox";
        String source = switch (direction) {
            case "INCOMING" -> incoming;
            case "OUTGOING" -> outgoing;
            default -> incoming + " UNION ALL " + outgoing;
        };
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM (" + source + ") activity", Long.class);
        long total = count == null ? 0 : count;
        var content = jdbc.query("SELECT * FROM (" + source + ") activity ORDER BY activity_at DESC, id DESC, direction LIMIT ? OFFSET ?", (rs, n) -> {
            Object stored = rs.getObject("envelope");
            byte[] bytes = stored instanceof byte[] b ? b : stored.toString().getBytes(StandardCharsets.UTF_8);
            var envelope = codec.decode(bytes);
            return new EventView(rs.getString("id"), rs.getString("direction"), envelope.eventType(),
                    envelope.sourceModule(), envelope.correlationId() == null ? null : envelope.correlationId().toString(),
                    rs.getString("status"), rs.getObject("activity_at", OffsetDateTime.class),
                    rs.getObject("completed_at", OffsetDateTime.class), rs.getString("error"));
        }, size, (long) page * size);
        int pages = (int) ((total + size - 1) / size);
        return new EventPage(content, page, size, total, pages, page == 0, page + 1 >= pages);
    }
}
