package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.exception.InvalidAnonymousTicketCredentialsException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AnonymousTicketAccessService;
import com.reclamos.backend.service.AttachmentAccessService;
import com.reclamos.backend.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AttachmentController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class AttachmentControllerTest {
    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final AuthenticatedIdentity CITIZEN = new AuthenticatedIdentity(
            "citizen", UUID.randomUUID(), "Citizen", null, ModuleRole.CITIZEN);
    private static final UsernamePasswordAuthenticationToken AUTHENTICATION =
            new UsernamePasswordAuthenticationToken(CITIZEN, null,
                    List.of(new SimpleGrantedAuthority("ROLE_CITIZEN")));

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private AttachmentAccessService attachmentAccessService;
    @MockitoBean
    private AnonymousTicketAccessService anonymousTicketAccessService;
    @MockitoBean
    private AuthService authService;

    @Test
    void identifiedDownloadStreamsPersistedMetadataAndSecurityHeaders() throws Exception {
        when(attachmentAccessService.download(eq(1L), any()))
                .thenReturn(download());

        mockMvc.perform(get("/api/attachments/1/content").with(authentication(AUTHENTICATION)))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().longValue("Content-Length", 3))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("proof.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void identifiedDownloadRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/attachments/1/content"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousDownloadRequiresValidHu34CredentialsAndSamePublicAttachment() throws Exception {
        when(anonymousTicketAccessService.authenticate("TRACK-1", "correct-password"))
                .thenReturn(new AnonymousTicketAccessService.AnonymousTicketAccess(TICKET_ID));
        when(attachmentAccessService.downloadAnonymous(1L, TICKET_ID)).thenReturn(download());

        mockMvc.perform(post("/api/tracking/actions/attachments/1/content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"TRACK-1\",\"anonymousAccessPassword\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{1, 2, 3}));

        when(anonymousTicketAccessService.authenticate(anyString(), eq("wrong-password")))
                .thenThrow(new InvalidAnonymousTicketCredentialsException());
        mockMvc.perform(post("/api/tracking/actions/attachments/1/content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"TRACK-1\",\"anonymousAccessPassword\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        when(anonymousTicketAccessService.authenticate(eq("WRONG"), anyString()))
                .thenThrow(new InvalidAnonymousTicketCredentialsException());
        mockMvc.perform(post("/api/tracking/actions/attachments/1/content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"WRONG\",\"anonymousAccessPassword\":\"some-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousForeignOrInternalAttachmentDoesNotRevealExistence() throws Exception {
        when(anonymousTicketAccessService.authenticate(anyString(), anyString()))
                .thenReturn(new AnonymousTicketAccessService.AnonymousTicketAccess(TICKET_ID));
        when(attachmentAccessService.downloadAnonymous(anyLong(), eq(TICKET_ID)))
                .thenThrow(new ResourceNotFoundException("El adjunto solicitado no existe"));

        mockMvc.perform(post("/api/tracking/actions/attachments/9/content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"TRACK-1\",\"anonymousAccessPassword\":\"correct-password\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void trackingCodeAloneNeverAuthorizesAnonymousDownload() throws Exception {
        mockMvc.perform(post("/api/tracking/actions/attachments/1/content")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingCode\":\"TRACK-1\"}"))
                .andExpect(status().isBadRequest());
    }

    private AttachmentAccessService.Download download() {
        return new AttachmentAccessService.Download(
                new ByteArrayResource(new byte[]{1, 2, 3}), "proof.pdf", "application/pdf", 3);
    }
}
