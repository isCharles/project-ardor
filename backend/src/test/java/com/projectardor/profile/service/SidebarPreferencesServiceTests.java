package com.projectardor.profile.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SidebarPreferencesServiceTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SidebarPreferencesService service = new SidebarPreferencesService(jdbc, mapper);
    private final UUID owner = UUID.randomUUID();

    private void saved(String json) {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(owner))).thenReturn(List.of(json));
    }
    private void successfulWrite(long revision) {
        when(jdbc.update(argThat(sql -> sql.contains("jsonb_set") && sql.contains("WHERE user_id=?") && sql.contains("revision")),
                anyString(), anyString(), eq(owner), eq(revision))).thenReturn(1);
    }

    @Test void defaultsDoNotWriteOrInitializeAccountPreferences() {
        saved("{}");
        var view = service.get(owner);
        assertThat(view.configured()).isFalse();
        assertThat(view.destinationIds()).containsExactly("chat", "job-search", "learning", "calendar");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void legacyPreferencesKeepMockRealAndProgressMeaning() {
        saved("""
                {"sidebar":{"hrefs":["/app","/app/interviews","/app/opportunities","/app/evidence"],"revision":3}}
                """);
        assertThat(service.get(owner).destinationIds()).containsExactly("chat", "learning.mock-interviews",
                "job-search.interviews", "learning.progress");
        assertThat(service.get(owner).revision()).isEqualTo(3);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void savesOwnedIdsAndLegacyProjectionAtomicallyAtExpectedVersion() {
        successfulWrite(3);
        var view = service.saveDestinations(owner, List.of("job-search", "learning.practice", "learning.practice"), 3);
        assertThat(view.destinationIds()).containsExactly("chat", "job-search", "learning.practice");
        assertThat(view.hrefs()).containsExactly("/app", "/app/opportunities", "/app/interviews");
        assertThat(view.revision()).isEqualTo(4);
        verify(jdbc).update(anyString(), argThat((String json) -> json.contains("hrefs") && !json.contains("destinationIds")),
                argThat((String json) -> json.contains("destinationIds")), eq(owner), eq(3L));
    }

    @Test void oldClientSavePreservesExistingDomainSelectionsWithSameCompatibilityUrl() {
        saved("""
                {"sidebar":{"hrefs":["/app","/app/opportunities","/app/interviews"],"revision":4},
                 "navigation":{"destinationIds":["chat","job-search","learning.practice"],"revision":4}}
                """);
        successfulWrite(4);
        assertThat(service.save(owner, List.of("/app", "/app/opportunities", "/app/interviews"), 4).destinationIds())
                .containsExactly("chat", "job-search", "learning.practice");
    }

    @Test void staleIdProjectionIsIgnoredAfterAnOlderServerWritesTheSidebar() {
        saved("""
                {"sidebar":{"hrefs":["/app","/app/cards"],"revision":5},
                 "navigation":{"destinationIds":["chat","job-search"],"revision":4}}
                """);
        assertThat(service.get(owner).destinationIds()).containsExactly("chat", "learning.flashcards");
    }

    @Test void rejectsStaleVersionsAndUnknownDestinationsWithoutOverwriting() {
        saved("""
                {"sidebar":{"hrefs":["/app"],"revision":2}}
                """);
        assertThatThrownBy(() -> service.saveDestinations(owner, List.of("learning.flashcards"), 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.save(owner, List.of("/app/admin"), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveDestinations(owner, List.of("admin"), 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.saveDestinations(owner, List.of(), Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.get(owner).revision()).isEqualTo(2);
    }

    @Test void everyReadAndWriteIsScopedToTheProvidedAuthenticatedOwner() {
        saved("{}");
        successfulWrite(0);
        service.get(owner);
        service.saveDestinations(owner, List.of("calendar"), 0);
        verify(jdbc).queryForList(argThat(sql -> sql.contains("WHERE user_id=?")), eq(String.class), eq(owner));
        verify(jdbc).update(anyString(), anyString(), anyString(), eq(owner), eq(0L));
    }

    @Test void frontendAndBackendPublishTheSameNavigationContract() throws Exception {
        Path base = Path.of(System.getProperty("basedir", "."));
        String frontend = Files.readString(base.resolve("../frontend/src/lib/navigation-catalog.json"));
        try (var input = new ClassPathResource("navigation-catalog.json").getInputStream()) {
            assertThat(mapper.readTree(frontend)).isEqualTo(mapper.readTree(input));
        }
    }
}
