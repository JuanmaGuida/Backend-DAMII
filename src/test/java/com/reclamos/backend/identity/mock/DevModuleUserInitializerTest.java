package com.reclamos.backend.identity.mock;

import com.reclamos.backend.entity.ModuleUser;
import com.reclamos.backend.identity.ExternalIdentityProfile;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.ModuleUserRepository;
import com.reclamos.backend.service.ModuleUserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DevModuleUserInitializerTest {
    @Test
    void provisionsTheFourMockProfilesWithLocalRolesAndArea() throws Exception {
        ModuleUserService moduleUserService = mock(ModuleUserService.class);
        ModuleUserRepository repository = mock(ModuleUserRepository.class);
        when(moduleUserService.synchronize(any())).thenAnswer(invocation -> {
            ExternalIdentityProfile profile = invocation.getArgument(0);
            ModuleUser user = new ModuleUser();
            user.setCitizenId(profile.citizenId());
            user.setFirstName(profile.firstName());
            user.setLastName(profile.lastName());
            user.setRole(ModuleRole.CITIZEN);
            user.setActive(true);
            return user;
        });

        new DevModuleUserInitializer(moduleUserService, repository).run(mock(ApplicationArguments.class));

        verify(moduleUserService, times(4)).synchronize(any());
        ArgumentCaptor<ModuleUser> savedUsers = ArgumentCaptor.forClass(ModuleUser.class);
        verify(repository, times(3)).save(savedUsers.capture());
        List<ModuleUser> values = savedUsers.getAllValues();
        assertEquals(ModuleRole.AGENT, values.get(0).getRole());
        assertNull(values.get(0).getAreaId());
        assertEquals(ModuleRole.AREA_RESPONSIBLE, values.get(1).getRole());
        assertEquals("M6", values.get(1).getAreaId());
        assertEquals(ModuleRole.ADMIN, values.get(2).getRole());
        assertNull(values.get(2).getAreaId());
    }
}
