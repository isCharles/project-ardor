package com.projectardor.profile.web;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.profile.service.SidebarPreferencesService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/profile/sidebar")
public class SidebarPreferencesController {
    private final SidebarPreferencesService preferences;
    public SidebarPreferencesController(SidebarPreferencesService preferences) { this.preferences = preferences; }
    @GetMapping
    public SidebarPreferencesService.View get(@AuthenticationPrincipal ArdorPrincipal principal) {
        return preferences.get(principal.userId());
    }
    @PutMapping
    public SidebarPreferencesService.View save(@AuthenticationPrincipal ArdorPrincipal principal, @Valid @RequestBody Update request) {
        if (!principal.userId().equals(request.expectedUserId())) throw new IllegalArgumentException("账户已切换，请重新加载侧栏设置");
        if ((request.hrefs() == null) == (request.destinationIds() == null)) {
            throw new IllegalArgumentException("请指定一组侧栏入口");
        }
        return request.destinationIds() == null
                ? preferences.save(principal.userId(), request.hrefs(), request.expectedRevision())
                : preferences.saveDestinations(principal.userId(), request.destinationIds(), request.expectedRevision());
    }
    public record Update(@Size(max = 32) List<String> hrefs, @NotNull @Min(0) Long expectedRevision,
            @NotNull java.util.UUID expectedUserId, @Size(max = 32) List<String> destinationIds) {}
}
