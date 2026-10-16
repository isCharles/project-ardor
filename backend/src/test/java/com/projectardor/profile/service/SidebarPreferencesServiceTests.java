package com.projectardor.profile.service;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SidebarPreferencesServiceTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SidebarPreferencesService service = new SidebarPreferencesService(jdbc, JsonMapper.builder().build());
    @Test void defaultsDoNotWriteOrInitializeAccountPreferences() {
        UUID owner = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(String.class), eq(owner))).thenReturn(List.of("null"));
        assertThat(service.get(owner).configured()).isFalse();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    @Test void savesOnlyTheOwnedSidebarKeyAtTheExpectedVersion() {
        UUID owner = UUID.randomUUID();
        when(jdbc.update(argThat(sql -> sql.contains("jsonb_set") && sql.contains("WHERE user_id=?") && sql.contains("revision")),
                anyString(), eq(owner), eq(3L))).thenReturn(1);
        var saved = service.save(owner, List.of("/app/learning", "/app/learning"), 3);
        assertThat(saved.hrefs()).containsExactly("/app", "/app/learning");
        assertThat(saved.revision()).isEqualTo(4);
        assertThat(saved.configured()).isTrue();
    }
    @Test void rejectsStaleVersionsAndInvalidModulesWithoutOverwritingPreferences() {
        UUID owner = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(String.class), eq(owner)))
                .thenReturn(List.of("{\"hrefs\":[\"/app\"],\"revision\":2}"));
        assertThatThrownBy(() -> service.save(owner, List.of("/app/cards"), 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.save(owner, List.of("/app/admin"), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(owner).revision()).isEqualTo(2);
    }
}
