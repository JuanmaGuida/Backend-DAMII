package com.reclamos.backend.identity.mock;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.ModuleUserRepository;
import com.reclamos.backend.service.ModuleUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevModuleUserInitializer implements ApplicationRunner {
    private final ModuleUserService moduleUserService;
    private final ModuleUserRepository moduleUserRepository;

    @Override
    public void run(ApplicationArguments args) {
        provision(MockIdentityProvider.CITIZEN_PROFILE, ModuleRole.CITIZEN, null);
        provision(MockIdentityProvider.AGENT_PROFILE, ModuleRole.AGENT, null);
        provision(MockIdentityProvider.AREA_RESPONSIBLE_PROFILE, ModuleRole.AREA_RESPONSIBLE, "M6");
        provision(MockIdentityProvider.ADMIN_PROFILE, ModuleRole.ADMIN, null);
    }

    private void provision(ExternalIdentityProfile profile, ModuleRole role, String areaId) {
        ModuleUser user = moduleUserService.synchronize(profile);
        if (user.getRole() == role && Objects.equals(user.getAreaId(), areaId)) {
            return;
        }
        user.setRole(role);
        user.setAreaId(areaId);
        moduleUserRepository.save(user);
    }
}
