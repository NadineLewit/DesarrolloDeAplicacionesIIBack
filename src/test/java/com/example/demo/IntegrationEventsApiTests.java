package com.example.demo;

import com.example.demo.infrastructure.integration.JsonSql;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationEventsApiTests {
    @LocalServerPort int port;
    @Autowired TestUsers users;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonSql jsonSql;
    @Autowired ObjectMapper json;

    HttpResponse<String> get(String query, boolean authenticated) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/public-works/integration-events" + query));
        if (authenticated) request.header("Authorization", "Bearer " + users.issueToken("events", "PERSONAL_OBRAS"));
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void listsBothDirectionsWithPaginationAndNoPayloads() throws Exception {
        UUID id = UUID.randomUUID();
        String envelope = "{\"eventId\":\"" + id + "\",\"eventType\":\"ticketUpdated\",\"eventVersion\":\"1.0\",\"sourceModule\":\"obras\",\"occurredAt\":\"2026-10-30T12:00:00Z\",\"data\":{\"private\":\"hidden\"}}";
        try {
            jdbc.update("INSERT INTO event_inbox(event_id,event_type,event_version,source_module,occurred_at,payload,envelope) VALUES (?, 'ticketUpdated','1.0','obras',CURRENT_TIMESTAMP,CAST(? AS JSONB),CAST(? AS JSONB))", id, jsonSql.parameter("{}"), jsonSql.parameter(envelope));
            jdbc.update("INSERT INTO event_outbox(id,event_id,ordering_key,payload,envelope) VALUES (?,?,'test',CAST(? AS JSONB),CAST(? AS JSONB))", id, id, jsonSql.parameter("{}"), jsonSql.parameter(envelope));
            assertEquals(401, get("", false).statusCode());
            var response = get("?size=100", true);
            assertEquals(200, response.statusCode());
            var body = json.readTree(response.body());
            assertTrue(body.path("totalElements").asLong() >= 2);
            boolean incoming = false, outgoing = false;
            for (var event : body.path("content")) {
                assertFalse(event.has("payload"));
                if (event.path("id").asString().equals(id.toString())) {
                    incoming |= event.path("direction").asString().equals("INCOMING");
                    outgoing |= event.path("direction").asString().equals("OUTGOING");
                }
            }
            assertTrue(incoming && outgoing);
            for (String direction : new String[]{"INCOMING", "OUTGOING"}) {
                var filtered = json.readTree(get("?direction=" + direction + "&size=1", true).body());
                assertEquals(1, filtered.path("content").size());
                assertEquals(direction, filtered.path("content").get(0).path("direction").asString());
            }
            assertEquals(0, json.readTree(get("?page=1000000", true).body()).path("content").size());
            assertEquals(400, get("?size=101", true).statusCode());
            assertEquals(400, get("?page=-1", true).statusCode());
            assertEquals(400, get("?direction=INVALID", true).statusCode());
        } finally {
            jdbc.update("DELETE FROM event_inbox WHERE event_id=?", id);
            jdbc.update("DELETE FROM event_outbox WHERE event_id=?", id);
        }
    }
}
