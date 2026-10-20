package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class TicketCancellationRequestServiceTest {
    @Mock TicketRepository tickets;
    @Mock TicketCancellationRequestRepository requests;
    @Mock TicketActivityRepository activities;
    @Mock TicketService ticketService;
    @Spy Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"),ZoneOffset.UTC);
    @InjectMocks TicketCancellationRequestService service;
    final UUID id=UUID.randomUUID(), owner=UUID.randomUUID(), staff=UUID.randomUUID(), requestId=UUID.randomUUID();
    Ticket ticket;
    @BeforeEach void setup() {
        ticket=new Ticket(); ticket.setId(id); ticket.setCitizenId(owner);
        ticket.setCurrentStatus(TicketStatus.REGISTERED); ticket.setStatusChangedAt(clock.instant());
        lenient().when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
    }
    @ParameterizedTest @EnumSource(value=TicketStatus.class,names={"REGISTERED"},mode=EnumSource.Mode.EXCLUDE)
    void incompatibleCitizenStatesNeverCreateRequest(TicketStatus status) {
        ticket.setCurrentStatus(status);
        assertThatThrownBy(()->service.create(id,input(),identity(owner,ModuleRole.CITIZEN))).isInstanceOf(TicketStateConflictException.class);
        verifyNoInteractions(requests,activities,ticketService);
    }
    @Test void ownerAndAnonymousIdentityAreEnforcedIndependentlyOfTicketIdKnowledge() {
        assertThatThrownBy(()->service.create(id,input(),identity(staff,ModuleRole.ADMIN))).isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(()->service.createAnonymous(id,input())).isInstanceOf(UnauthorizedTicketOperationException.class);
        ticket.setAnonymous(true); ticket.setCitizenId(null);
        assertThatThrownBy(()->service.create(id,input(),identity(owner,ModuleRole.CITIZEN))).isInstanceOf(UnauthorizedTicketOperationException.class);
    }
    @Test void noChildrenAndDuplicateRelationshipAreRejected() {
        when(tickets.findActiveDuplicateIds(id)).thenReturn(List.of());
        assertThatThrownBy(()->service.create(id,input(),identity(owner,ModuleRole.CITIZEN))).isInstanceOf(TicketStateConflictException.class);
        ticket.setMainTicket(new Ticket());
        assertThatThrownBy(()->service.create(id,input(),identity(owner,ModuleRole.CITIZEN))).isInstanceOf(TicketStateConflictException.class);
    }
    @Test void requestAuditsAccreditationWithoutChangingTicketOrCallingLifecycle() {
        ticket.setAnonymous(true);ticket.setCitizenId(null);
        when(tickets.findActiveDuplicateIds(id)).thenReturn(List.of(UUID.randomUUID()));
        when(requests.saveAndFlush(any())).thenAnswer(call->{ TicketCancellationRequest r=call.getArgument(0);r.setId(requestId);return r; });
        var result=service.createAnonymous(id,input());
        assertThat(result.requesterKind()).isEqualTo(CancellationRequesterKind.ANONYMOUS);
        assertThat(result.requestedByCitizenId()).isNull(); assertThat(result.cancellationScope()).isNull();
        assertThat(ticket.getCurrentStatus()).isEqualTo(TicketStatus.REGISTERED);
        verify(tickets,never()).save(any()); verifyNoInteractions(ticketService);
        ArgumentCaptor<TicketActivity> audit=ArgumentCaptor.forClass(TicketActivity.class);verify(activities).save(audit.capture());
        assertThat(audit.getValue().getMessage()).contains("acreditado");assertThat(audit.getValue().getActorId()).isNull();
    }
    @Test void emptyReasonMissingCodeAndOversizedReasonAreRejectedBeforePersistence() {
        when(tickets.findActiveDuplicateIds(id)).thenReturn(List.of(UUID.randomUUID()));
        for(var invalid:List.of(new CreateCancellationRequest(null,"motivo"),new CreateCancellationRequest(CancellationReasonCode.OTHER," "),new CreateCancellationRequest(CancellationReasonCode.OTHER,"x".repeat(4001))))
            assertThatThrownBy(()->service.create(id,invalid,identity(owner,ModuleRole.CITIZEN))).isInstanceOf(InvalidTicketRequestException.class);
        verify(requests,never()).saveAndFlush(any());
    }
    @ParameterizedTest @EnumSource(value=ModuleRole.class,names={"CITIZEN","AREA_RESPONSIBLE"})
    void staffReadServiceAlsoChecksRoles(ModuleRole role) {
        assertThatThrownBy(()->service.staffHistory(id,identity(staff,role))).isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(()->service.staffGet(id,requestId,identity(staff,role))).isInstanceOf(UnauthorizedTicketOperationException.class);
    }
    @Test void approvalRequiresScopeAndRationaleEvenOnRetry() {
        TicketCancellationRequest r=pending();when(requests.findByIdAndTicket_Id(requestId,id)).thenReturn(Optional.of(r));
        assertThatThrownBy(()->service.approve(id,requestId,new ReviewCancellationRequest(null,"Motivo",null),identity(staff,ModuleRole.AGENT))).isInstanceOf(InvalidTicketRequestException.class);
        assertThatThrownBy(()->service.approve(id,requestId,new ReviewCancellationRequest(CancellationScope.GLOBAL," ",null),identity(staff,ModuleRole.AGENT))).isInstanceOf(InvalidTicketRequestException.class);
        verify(ticketService,never()).cancelWithScope(any(),any(),any());
    }
    @Test void changedTimestampRequiresReviewEvenWithSameStatusAndChildren() {
        TicketCancellationRequest r=pending();r.setRequestedStatusChangedAt(clock.instant().minusSeconds(1));
        when(requests.findByIdAndTicket_Id(requestId,id)).thenReturn(Optional.of(r));
        assertThatThrownBy(()->service.approve(id,requestId,new ReviewCancellationRequest(CancellationScope.GLOBAL,"Motivo",null),identity(staff,ModuleRole.AGENT))).isInstanceOf(TicketStateConflictException.class);
        verify(ticketService,never()).cancelWithScope(any(),any(),any());
    }
    @Test void missingTicketAndForeignRequestAreNotFound() {
        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.create(id,input(),identity(owner,ModuleRole.CITIZEN))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(()->service.staffGet(id,requestId,identity(staff,ModuleRole.ADMIN))).isInstanceOf(ResourceNotFoundException.class);
    }
    TicketCancellationRequest pending() { var r=new TicketCancellationRequest();r.setId(requestId);r.setTicket(ticket);r.setStatus(CancellationRequestStatus.PENDING);r.setRequestedStatusChangedAt(clock.instant());r.setDuplicateSnapshot(List.of());return r; }
    CreateCancellationRequest input(){return new CreateCancellationRequest(CancellationReasonCode.WITHDRAWN_BY_CITIZEN,"Retirar");}
    AuthenticatedIdentity identity(UUID citizen,ModuleRole role){return new AuthenticatedIdentity(citizen.toString(),citizen,"Test",null,role);}
}
