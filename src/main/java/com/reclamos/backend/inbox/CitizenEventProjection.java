package com.reclamos.backend.inbox;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.ModuleUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class CitizenEventProjection {
    private final ModuleUserRepository users;
    private final Clock clock;
    public CitizenEventProjection(ModuleUserRepository users, Clock clock) { this.users = users; this.clock = clock; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(CoreIncomingEvent event) {
        var id = CoreIncomingEventValidator.uuid(event.data().get("citizenId"));
        users.lockActorIdentity(id);
        ModuleUser user = users.findByActorIdForUpdate(id).orElse(null);
        if (user != null && (user.getIdentitySource() != IdentitySource.M1 || user.getActorType() != IdentityActorType.CIUDADANO
                || !id.equals(user.getCitizenId()))) throw new IncomingEventFailure(IncomingEventFailure.Code.WRONG_PRODUCER);
        if (user == null) {
            user = new ModuleUser(); user.setActorId(id); user.setCitizenId(id);
            user.setActorType(IdentityActorType.CIUDADANO); user.setIdentitySource(IdentitySource.M1);
            user.setRole(ModuleRole.CITIZEN); user.setLastSyncedAt(clock.instant());
        }
        String type = event.data().get("updateType").asText();
        Instant time = event.occurredAt();
        switch (type) {
            case "REGISTERED" -> {
                boolean newerProfile = newer(time, user.getExternalProfileUpdatedAt());
                if (newerProfile || time.equals(user.getExternalProfileUpdatedAt())) {
                    var details = event.data().get("details");
                    if (details.hasNonNull("name") && (newerProfile || user.getFirstName() == null)) user.setFirstName(details.get("name").asText());
                    if (details.hasNonNull("lastname") && (newerProfile || user.getLastName() == null)) user.setLastName(details.get("lastname").asText());
                    user.setExternalProfileUpdatedAt(time);
                }
                // REGISTERED is not an unblock event, regardless of its timestamp.
                if (user.getExternalStatus() == ExternalCitizenStatus.UNKNOWN) user.setExternalStatus(ExternalCitizenStatus.ACTIVE);
                if (newer(time, user.getExternalStatusUpdatedAt())) user.setExternalStatusUpdatedAt(time);
            }
            case "BLOCKED", "DECEASED" -> {
                // Restrictions are sticky. Death cannot be undone by a blocked/registered replay.
                if (type.equals("DECEASED")) user.setExternalStatus(ExternalCitizenStatus.DECEASED);
                else if (user.getExternalStatus() != ExternalCitizenStatus.DECEASED) user.setExternalStatus(ExternalCitizenStatus.BLOCKED);
                if (newer(time, user.getExternalStatusUpdatedAt())) user.setExternalStatusUpdatedAt(time);
            }
            case "ADDRESS_UPDATED" -> {
                // No current M2 feature needs a citizen address. Remember ordering only, never alter ticket locations.
                if (newer(time, user.getExternalAddressUpdatedAt())) user.setExternalAddressUpdatedAt(time);
            }
            default -> throw new IncomingEventFailure(IncomingEventFailure.Code.INVALID_EVENT);
        }
        users.saveAndFlush(user);
    }
    private boolean newer(Instant incoming, Instant previous) { return previous == null || incoming.isAfter(previous); }
}
