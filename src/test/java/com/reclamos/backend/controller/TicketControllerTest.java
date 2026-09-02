package com.reclamos.backend.controller;

import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketController.class)
class TicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketService ticketService;

    @Test
    void listPassesFiltersAndPagingToServiceAndReturnsPagedBody() throws Exception {
        TicketResponse response = new TicketResponse();
        response.setId(UUID.randomUUID());
        response.setCurrentStatus(TicketStatus.ROUTED);
        Page<TicketResponse> page = new PageImpl<>(List.of(response));
        when(ticketService.listTickets(any(TicketFilter.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/tickets")
                        .param("priority", "HIGH")
                        .param("status", "ROUTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].currentStatus").value("ROUTED"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void startReviewDelegatesToServiceAndReturnsOk() throws Exception {
        UUID ticketId = UUID.randomUUID();
        TicketResponse response = new TicketResponse();
        response.setId(ticketId);
        response.setCurrentStatus(TicketStatus.IN_REVIEW);
        when(ticketService.startReview(eq(ticketId), any())).thenReturn(response);

        mockMvc.perform(post("/api/tickets/{ticketId}/review", ticketId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("IN_REVIEW"));
    }

    @Test
    void startReviewOnWrongStateReturnsConflict() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.startReview(eq(ticketId), any()))
                .thenThrow(new TicketStateConflictException("El ticket no está REGISTERED"));

        mockMvc.perform(post("/api/tickets/{ticketId}/review", ticketId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El ticket no está REGISTERED"));
    }

    @Test
    void startReviewOnMissingTicketReturnsNotFound() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.startReview(eq(ticketId), any()))
                .thenThrow(new ResourceNotFoundException("El ticket solicitado no existe"));

        mockMvc.perform(post("/api/tickets/{ticketId}/review", ticketId))
                .andExpect(status().isNotFound());
    }

    @Test
    void correctClassificationRequiresRequestTypeId() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(patch("/api/tickets/{ticketId}/classification", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void correctClassificationDelegatesToService() throws Exception {
        UUID ticketId = UUID.randomUUID();
        TicketResponse response = new TicketResponse();
        response.setId(ticketId);
        response.setRequestTypeCode("FLOODING");
        when(ticketService.correctClassification(eq(ticketId), eq(20L), any())).thenReturn(response);

        mockMvc.perform(patch("/api/tickets/{ticketId}/classification", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestTypeId\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestTypeCode").value("FLOODING"));
    }

    @Test
    void correctClassificationOnStateConflictReturnsConflict() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.correctClassification(eq(ticketId), eq(20L), any()))
                .thenThrow(new TicketStateConflictException("La clasificación ya fue finalizada"));

        mockMvc.perform(patch("/api/tickets/{ticketId}/classification", ticketId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestTypeId\":20}"))
                .andExpect(status().isConflict());
    }
}
