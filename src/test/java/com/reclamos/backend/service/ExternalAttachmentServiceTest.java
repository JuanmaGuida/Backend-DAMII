package com.reclamos.backend.service;

import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalAttachmentServiceTest {
    final ExternalAttachmentReferenceRepository refs=mock(ExternalAttachmentReferenceRepository.class);
    final TicketRepository tickets=mock(TicketRepository.class);
    final ExternalAttachmentService service=new ExternalAttachmentService(refs,tickets);
    final UUID ticketId=UUID.randomUUID(),owner=UUID.randomUUID();
    final Ticket ticket=new Ticket();
    ExternalAttachmentServiceTest() {
        ticket.setId(ticketId); ticket.setCitizenId(owner); ticket.setResponsibleAreaId("M3");
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
    }
    @ParameterizedTest @ValueSource(strings={"file:///etc/passwd","javascript:alert(1)","//example.test/file","https://user:password@example.test/file","invalid"})
    void rejectsUnsafeOrCredentialBearingReferencesWithoutPerformingIO(String url) {
        assertThatThrownBy(() -> service.validate(List.of(new UpdateTicketStatusRequest.ExternalAttachment("photo.jpg","image/jpeg",url,null))))
                .isInstanceOf(InvalidTicketRequestException.class).hasMessage("Referencia de evidencia externa inválida");
        verifyNoInteractions(refs);
    }
    @ParameterizedTest @ValueSource(strings={"M3","M4","M7"})
    void preservesOptionalSizeAndRecordsEventProvenanceWithInternalVisibility(String area) {
        ticket.setResponsibleAreaId(area);
        var attachments=List.of(new UpdateTicketStatusRequest.ExternalAttachment("photo.jpg","image/jpeg","https://example.test/photo",null));
        service.validate(attachments);
        UUID event=UUID.randomUUID(); var when=Instant.parse("2026-10-01T12:00:00Z");
        var request=new UpdateTicketStatusRequest(ticketId,UpdateTicketStatusType.PROGRESS,null,null,null,null,
                new UpdateTicketStatusRequest.Actor("SYSTEM",null),when,attachments);
        service.record(ticket,event,area,request);
        var captor=org.mockito.ArgumentCaptor.forClass(ExternalAttachmentReference.class);
        verify(refs).save(captor.capture());
        var ref=captor.getValue(); assertThat(ref.getExternalEventId()).isEqualTo(event);
        assertThat(ref.getSizeBytes()).isNull(); assertThat(ref.getVisibility()).isEqualTo(MessageVisibility.INTERNAL);
        assertThat(ref.getUpdateType()).isEqualTo(UpdateTicketStatusType.PROGRESS);
        assertThat(ref.getOccurredAt()).isEqualTo(when);
    }
    @ParameterizedTest @ValueSource(strings={"M3","M4","M7"})
    void ownerSeesOnlyPublicReferencesAndStaffMustHaveTicketScope(String area) {
        ticket.setResponsibleAreaId(area);
        var internal=reference(MessageVisibility.INTERNAL); var publicRef=reference(MessageVisibility.PUBLIC);
        when(refs.findAllByTicket_IdOrderByOccurredAtAscIdAsc(ticketId)).thenReturn(List.of(internal,publicRef));
        assertThat(service.list(ticketId,identity(owner,ModuleRole.CITIZEN,null))).hasSize(1);
        assertThat(service.list(ticketId,identity(UUID.randomUUID(),ModuleRole.AGENT,null))).hasSize(2);
        assertThat(service.list(ticketId,identity(UUID.randomUUID(),ModuleRole.AREA_RESPONSIBLE,area))).hasSize(2);
        assertThatThrownBy(() -> service.list(ticketId,identity(UUID.randomUUID(),ModuleRole.AREA_RESPONSIBLE,"M6")))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(() -> service.list(ticketId,identity(UUID.randomUUID(),ModuleRole.CITIZEN,null)))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(() -> service.list(ticketId,null)).isInstanceOf(UnauthorizedTicketOperationException.class);
    }
    ExternalAttachmentReference reference(MessageVisibility visibility) {
        var ref=new ExternalAttachmentReference(); ref.setVisibility(visibility); ref.setTicket(ticket);
        ref.setUrl("https://example.test/photo"); return ref;
    }
    AuthenticatedIdentity identity(UUID id,ModuleRole role,String area) {
        return new AuthenticatedIdentity("test",id,"Test",area,role);
    }
}
