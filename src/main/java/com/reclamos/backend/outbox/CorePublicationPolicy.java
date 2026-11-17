package com.reclamos.backend.outbox;

import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.identity.IdentityActorType;
import com.reclamos.backend.identity.IdentitySource;
import com.reclamos.backend.repository.ModuleUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Admission is decided when the ticket/event is created, never by backfilling pending events. */
@Component
public final class CorePublicationPolicy {
    private final boolean automatic;
    private final boolean realOriginEnabled;
    private final boolean localQa;
    private final ModuleUserRepository users;

    @Autowired
    public CorePublicationPolicy(@Value("${app.core.outbox.auto-approve-new-events:false}") boolean automatic,
                                 @Value("${app.core.outbox.enabled:false}") boolean publishing,
                                 @Value("${app.identity.provider:MOCK}") String identityProvider,
                                 @Value("${app.simulator.enabled:false}") boolean simulator,
                                 @Value("${app.core.outbox.approved-event-ids:}") String approved,
                                 ModuleUserRepository users,
                                 @Value("${app.core.outbox.local-qa:false}") boolean localQa,
                                 @Value("${app.core.outbox.host:}") String host,
                                 Environment environment) {
        if (localQa && (environment.getActiveProfiles().length != 2
                || !environment.matchesProfiles("dev & qa-isolated")
                || !"MOCK".equals(identityProvider) || !"rabbitmq".equals(host)
                || !automatic || !publishing || simulator || (approved != null && !approved.isBlank()))) {
            throw new IllegalStateException("Local QA publication requires only dev + qa-isolated profiles, MOCK identity, rabbitmq host, publishing and automatic mode without simulator or manual approvals");
        }
        if (automatic && (!publishing || (!"M1".equals(identityProvider) && !localQa) || simulator
                || (approved != null && !approved.isBlank()))) {
            throw new IllegalStateException("Automatic Core publication requires publishing enabled, M1 identity, simulator disabled and an empty manual approval list");
        }
        this.automatic = automatic;
        this.realOriginEnabled = "M1".equals(identityProvider) && !simulator;
        this.localQa = localQa;
        this.users = users;
    }

    public CorePublicationPolicy(boolean automatic, boolean publishing, String identityProvider,
                                 boolean simulator, String approved, ModuleUserRepository users) {
        this(automatic, publishing, identityProvider, simulator, approved, users, false, "", null);
    }

    public boolean automatic() { return automatic; }

    /** Called only on initial ticket creation. Historical and MOCK anonymous tickets stay unverified. */
    public boolean realOriginForNewTicket(Ticket ticket) {
        if (!realOriginEnabled) return false;
        if (ticket.isAnonymous()) return ticket.getCitizenId() == null;
        var citizen = ticket.getCitizenId();
        return citizen != null && users.findByCitizenId(citizen)
                .filter(user -> user.getIdentitySource() == IdentitySource.M1
                        && user.getActorType() == IdentityActorType.CIUDADANO
                        && citizen.equals(user.getActorId())).isPresent();
    }

    /** No actor is required: legitimate SLA/timeout processes use the ticket's persisted provenance. */
    public boolean eligibleNewEvent(Ticket ticket) {
        return automatic && (localQa ? ticket.isAnonymous() && ticket.getCitizenId() == null
                : ticket.isCoreOriginVerified());
    }
}
