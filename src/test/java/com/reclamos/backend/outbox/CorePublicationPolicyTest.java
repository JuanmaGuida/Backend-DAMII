package com.reclamos.backend.outbox;

import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.identity.IdentitySource;
import com.reclamos.backend.repository.ModuleUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorePublicationPolicyTest {
    private final ModuleUserRepository users=mock(ModuleUserRepository.class);
    private CorePublicationPolicy policy(boolean automatic,String provider,boolean simulator) {
        return new CorePublicationPolicy(automatic,true,provider,simulator,"",users);
    }
    @Test void defaultConfigurationNeverAutomaticallyAuthorizesAnEvent() {
        new ApplicationContextRunner().withUserConfiguration(CorePublicationPolicy.class)
                .withBean(ModuleUserRepository.class,() -> users).run(ctx -> {
                    var policy=ctx.getBean(CorePublicationPolicy.class);
                    assertThat(policy.automatic()).isFalse();
                    var ticket=new Ticket(); ticket.setCoreOriginVerified(true);
                    assertThat(policy.eligibleNewEvent(ticket)).isFalse();
                });
    }
    @Test void rejectsMockDisabledPublisherSimulatorAndMixingModesWithoutEchoingApprovals() {
        assertThatThrownBy(() -> policy(true,"MOCK",false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> policy(true,"M1",true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CorePublicationPolicy(true,false,"M1",false,"",users)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CorePublicationPolicy(true,true,"M1",false,"sensitive-invalid-approval",users))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("sensitive-invalid-approval");
    }
    @Test void newIdentifiedTicketRequiresCanonicalM1IdentityAtCreation() {
        var event=OutboxFixtures.event("ticketCreated"); var ticket=event.getTicket();
        var user=OutboxFixtures.realCitizen(event); var policy=policy(true,"M1",false);
        when(users.findByCitizenId(ticket.getCitizenId())).thenReturn(Optional.empty());
        assertThat(policy.realOriginForNewTicket(ticket)).isFalse();
        when(users.findByCitizenId(ticket.getCitizenId())).thenReturn(Optional.of(user));
        assertThat(policy.realOriginForNewTicket(ticket)).isTrue();
        user.setIdentitySource(IdentitySource.MOCK);
        assertThat(policy.realOriginForNewTicket(ticket)).isFalse();
    }
    @Test void anonymousCreationAndBackgroundFactsNeedNoHumanButRequirePersistedRealOrigin() {
        var ticket=new Ticket(); ticket.setAnonymous(true); var policy=policy(true,"M1",false);
        assertThat(policy.realOriginForNewTicket(ticket)).isTrue();
        assertThat(policy.eligibleNewEvent(ticket)).isFalse(); // Not a new verified ticket just because M1 is enabled.
        ticket.setCoreOriginVerified(policy.realOriginForNewTicket(ticket));
        assertThat(policy.eligibleNewEvent(ticket)).isTrue();
        assertThat(policy(false,"M1",false).eligibleNewEvent(ticket)).isFalse();
        verifyNoInteractions(users);
    }
    @Test void mockOrSimulatorTicketsNeverAcquireProvenanceEvenWithoutCitizenId() {
        var ticket=new Ticket(); ticket.setAnonymous(true);
        ticket.setCoreOriginVerified(policy(false,"MOCK",false).realOriginForNewTicket(ticket));
        assertThat(ticket.isCoreOriginVerified()).isFalse();
        assertThat(policy(true,"M1",false).eligibleNewEvent(ticket)).isFalse();
        assertThat(policy(false,"M1",true).realOriginForNewTicket(ticket)).isFalse();
        verifyNoInteractions(users);
    }
    @Test void m1ManualTicketCanHaveRealOriginWithoutApprovingItsEventsAutomatically() {
        var ticket=new Ticket(); ticket.setAnonymous(true); var policy=policy(false,"M1",false);
        ticket.setCoreOriginVerified(policy.realOriginForNewTicket(ticket));
        assertThat(ticket.isCoreOriginVerified()).isTrue(); assertThat(policy.eligibleNewEvent(ticket)).isFalse();
    }
    @Test void eligibilityNeverUsesTheOwnersLaterProfileOrTheCurrentClock() {
        var ticket=OutboxFixtures.event("ticketCreated").getTicket(); ticket.setCoreOriginVerified(true);
        assertThat(policy(true,"M1",false).eligibleNewEvent(ticket)).isTrue();
        verifyNoInteractions(users);
    }
}
