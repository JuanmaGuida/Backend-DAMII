package com.reclamos.backend.controller;

import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.service.TicketStatusUpdateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 3.4 / DDA2-61: cobertura del endpoint interno de simulación. El
 * endpoint solo existe como bean cuando app.simulator.enabled=true (ver
 * TicketSimulationController), así que este slice test lo fuerza vía
 * @TestPropertySource para poder cargarlo — en application.properties
 * "real" el default es false.
 */
@WebMvcTest(TicketSimulationController.class)
@TestPropertySource(properties = "app.simulator.enabled=true")
class TicketSimulationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketStatusUpdateService ticketStatusUpdateService;

    @Test
    void simulateStatusUpdateDelegatesToServiceAndReturnsOk() throws Exception {
        UUID ticketId = UUID.randomUUID();
        TicketResponse response = new TicketResponse();
        response.setId(ticketId);
        response.setCurrentStatus(TicketStatus.IN_PROGRESS);
        when(ticketStatusUpdateService.applyUpdate(eq(ticketId), any())).thenReturn(response);

        String body = """
                {
                  "updateType": "STARTED",
                  "publicMessage": "Comenzamos a trabajar en esto.",
                  "updatedBy": {"type": "AREA_USER", "id": "USR-M6-77"},
                  "updateOccurredAt": "2026-09-02T12:00:00Z",
                  "producerModuleId": "M6"
                }
                """;

        mockMvc.perform(post("/api/tickets/{ticketId}/simulate-status-update", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("IN_PROGRESS"));
    }

    @Test
    void simulateStatusUpdateWithoutUpdateTypeReturnsBadRequest() throws Exception {
        UUID ticketId = UUID.randomUUID();

        String body = """
                {
                  "updatedBy": {"type": "AREA_USER", "id": "USR-M6-77"},
                  "updateOccurredAt": "2026-09-02T12:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/tickets/{ticketId}/simulate-status-update", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simulateStatusUpdateOnIncompatibleStateReturnsConflict() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketStatusUpdateService.applyUpdate(eq(ticketId), any()))
                .thenThrow(new TicketStateConflictException("El ticket no está ROUTED"));

        String body = """
                {
                  "updateType": "STARTED",
                  "updatedBy": {"type": "AREA_USER", "id": "USR-M6-77"},
                  "updateOccurredAt": "2026-09-02T12:00:00Z"
                }
                """;

        mockMvc.perform(post("/api/tickets/{ticketId}/simulate-status-update", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El ticket no está ROUTED"));
    }
}
