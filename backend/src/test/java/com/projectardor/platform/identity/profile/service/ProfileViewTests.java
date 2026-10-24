package com.projectardor.platform.identity.profile.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.platform.identity.profile.domain.UserProfile;
import com.projectardor.platform.identity.profile.repository.UserProfileRepository;
import com.projectardor.platform.identity.profile.web.ProfileResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProfileViewTests {
    private final UUID owner = UUID.randomUUID(), foreign = UUID.randomUUID();
    private final UserProfileRepository repository = mock(UserProfileRepository.class);
    private final ProfileService service = new ProfileService(repository);

    @Test void ownedReadReturnsDetachedRolesWithoutIdentityOrPreferences() {
        var entity = UserProfile.create(owner, "Owner");
        entity.update("Owner", null, List.of("Backend"), "Asia/Shanghai");
        when(repository.findByUserId(owner)).thenReturn(Optional.of(entity));
        var view = service.view(owner);
        entity.update("Changed", "Changed", List.of("Other"), "UTC");
        assertThat(view.displayName()).isEqualTo("Owner");
        assertThat(view.headline()).isNull();
        assertThat(view.targetRoles()).containsExactly("Backend");
        assertThat(view.timezone()).isEqualTo("Asia/Shanghai");
        assertThatThrownBy(() -> view.targetRoles().add("injected")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> service.view(foreign)).isInstanceOf(IllegalStateException.class).hasMessage("用户资料不存在");
        verify(repository).findByUserId(foreign);
        verify(repository, never()).save(any());
    }

    @Test void updateKeepsExistingNormalizationAndSnapshotsTheChangedValues() {
        var entity = UserProfile.create(owner, "Old");
        when(repository.findByUserId(owner)).thenReturn(Optional.of(entity));
        var view = service.updateView(owner, " New ", "  ", Arrays.asList(" Backend ", null, "", "Backend", "Designer"), " UTC ");
        assertThat(view).isEqualTo(new ProfileView("New", null, List.of("Backend", "Designer"), "UTC"));
        assertThat(entity.getTargetRoles()).containsExactly("Backend", "Designer");
        assertThat(service.updateView(owner, null, null, List.of(), null).timezone()).isEqualTo("Asia/Shanghai");
        verify(repository, never()).save(any()); // Existing managed dirty-check update, no new write path.
    }

    @Test void invalidTimezoneAndMissingOwnerDoNotMutateTheProfile() {
        var entity = UserProfile.create(owner, "Old");
        when(repository.findByUserId(owner)).thenReturn(Optional.of(entity));
        assertThatThrownBy(() -> service.updateView(owner, "New", null, List.of(), "not-a-zone"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(entity.getDisplayName()).isEqualTo("Old");
        assertThatThrownBy(() -> service.updateView(foreign, "Other", null, List.of(), "UTC"))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test void fourHttpFieldsAndNullsStayStableAndInputListIsCopied() {
        var roles = new ArrayList<>(List.of("Backend"));
        var view = new ProfileView(null, null, roles, "UTC"); roles.clear();
        var json = JsonMapper.builder().build();
        JsonNode wire = json.valueToTree(ProfileResponse.from(view)), application = json.valueToTree(view);
        assertThat(wire).isEqualTo(application);
        assertThat(wire.propertyNames()).containsExactly("displayName", "headline", "targetRoles", "timezone");
        assertThat(wire.path("displayName").isNull()).isTrue();
        assertThat(view.targetRoles()).containsExactly("Backend");
    }

    @Test void applicationTransactionsPreserveReadAndWriteBoundaries() throws Exception {
        assertThat(ProfileService.class.getMethod("view", UUID.class).getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(ProfileService.class.getMethod("updateView", UUID.class, String.class, String.class, List.class, String.class)
                .getAnnotation(Transactional.class).readOnly()).isFalse();
        assertThat(java.lang.reflect.Modifier.isPrivate(ProfileService.class.getDeclaredMethod("get", UUID.class).getModifiers())).isTrue();
        assertThat(java.lang.reflect.Modifier.isPrivate(ProfileService.class.getDeclaredMethod("update", UUID.class, String.class, String.class, List.class, String.class).getModifiers())).isTrue();
    }
}
