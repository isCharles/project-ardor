package com.projectardor.platform.identity.profile.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.platform.identity.profile.web.SidebarPreferencesController;

class SidebarPreferencesControllerTests {
    private final SidebarPreferencesService service = mock(SidebarPreferencesService.class);
    private final SidebarPreferencesController controller = new SidebarPreferencesController(service);
    private final ArdorPrincipal user = new ArdorPrincipal(UUID.randomUUID(), "sidebar@ardor.invalid", "unused", true, UserRole.USER);

    @Test void readsOnlyAuthenticatedOwner() {
        controller.get(user);
        verify(service).get(user.userId());
    }

    @Test void staleAccountCannotWriteEitherProtocol() {
        UUID other = UUID.randomUUID();
        for (var request : List.of(
                new SidebarPreferencesController.Update(List.of("/app"), 0L, other, null),
                new SidebarPreferencesController.Update(null, 0L, other, List.of("chat")))) {
            assertThatThrownBy(() -> controller.save(user, request)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(service);
    }

    @Test void ambiguousOrMissingProtocolCannotWrite() {
        for (var request : List.of(
                new SidebarPreferencesController.Update(null, 0L, user.userId(), null),
                new SidebarPreferencesController.Update(List.of("/app"), 0L, user.userId(), List.of("chat")))) {
            assertThatThrownBy(() -> controller.save(user, request)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(service);
    }

    @Test void newAndOldClientsDispatchWithAuthenticatedOwnerAndExpectedRevision() {
        controller.save(user, new SidebarPreferencesController.Update(null, 3L, user.userId(), List.of("job-search")));
        controller.save(user, new SidebarPreferencesController.Update(List.of("/app/opportunities"), 4L, user.userId(), null));
        verify(service).saveDestinations(user.userId(), List.of("job-search"), 3L);
        verify(service).save(user.userId(), List.of("/app/opportunities"), 4L);
        verifyNoMoreInteractions(service);
    }
}
