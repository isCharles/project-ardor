package com.projectardor.learning.web;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.learning.domain.LearningOriginKind;
import com.projectardor.learning.service.LearningContextInput;
import com.projectardor.learning.service.LearningContextResolver;

/** Read-only source navigation for proposals that have not created a plan yet. */
@RestController
@RequestMapping("/api/learning-plans/origins")
public class LearningOriginController {
    private final LearningContextResolver contexts;
    public LearningOriginController(LearningContextResolver contexts) { this.contexts = contexts; }

    @GetMapping("/{kind}/{id}")
    public Reference reference(@AuthenticationPrincipal ArdorPrincipal principal,
            @PathVariable LearningOriginKind kind, @PathVariable UUID id) {
        if (kind != LearningOriginKind.INTERVIEW_RECORD && kind != LearningOriginKind.INTERVIEW_QUESTION
                && kind != LearningOriginKind.RESUME && kind != LearningOriginKind.KNOWLEDGE)
            throw new IllegalArgumentException("此来源类型没有可打开的资源");
        var source = contexts.resolve(principal.userId(), null, null, new LearningContextInput(kind, id, null, null));
        return new Reference(source.kind(), source.resourceId(), source.recordId());
    }

    public record Reference(LearningOriginKind kind, UUID resourceId, UUID recordId) {}
}
