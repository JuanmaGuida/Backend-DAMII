package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.UpdateTicketStatusType;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AuthService;
import com.reclamos.backend.service.ExternalAttachmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ExternalAttachmentController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class ExternalAttachmentControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ExternalAttachmentService service;
    @MockitoBean AuthService auth;
    final UUID ticketId=UUID.randomUUID();
    final AuthenticatedIdentity staff=new AuthenticatedIdentity("staff",UUID.randomUUID(),"Agent",null,ModuleRole.AGENT);
    final UsernamePasswordAuthenticationToken principal=new UsernamePasswordAuthenticationToken(staff,null,
            List.of(new SimpleGrantedAuthority("ROLE_AGENT")));

    @Test void anonymousCannotReadReferences() throws Exception {
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void authorizedMetadataIsNotCachedRedirectedOrStreamedAsALocalFile() throws Exception {
        when(service.list(ticketId,staff)).thenReturn(List.of(new ExternalAttachmentService.Reference(1L,UUID.randomUUID(),
                UpdateTicketStatusType.RESOLVED,Instant.parse("2026-10-01T12:00:00Z"),"photo.jpg","image/jpeg",
                "https://external.invalid/photo",null,MessageVisibility.INTERNAL)));
        mvc.perform(get(path()).with(authentication(principal))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$[0].visibility").value("INTERNAL"))
                .andExpect(jsonPath("$[0].url").value("https://external.invalid/photo"))
                .andExpect(jsonPath("$[0].storageKey").doesNotExist());
    }
    @Test void ticketAuthorizationFailureIsPropagated() throws Exception {
        when(service.list(eq(ticketId),any())).thenThrow(new UnauthorizedTicketOperationException());
        mvc.perform(get(path()).with(authentication(principal))).andExpect(status().isForbidden());
    }
    String path() { return "/api/tickets/"+ticketId+"/external-attachments"; }
}
