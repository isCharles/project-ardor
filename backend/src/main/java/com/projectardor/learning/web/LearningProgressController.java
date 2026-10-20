package com.projectardor.learning.web;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.projectardor.auth.security.ArdorPrincipal;
import com.projectardor.learning.service.LearningProgressService;

@RestController
@RequestMapping("/api/learning-progress")
public class LearningProgressController {
    private final LearningProgressService progress;
    public LearningProgressController(LearningProgressService progress) { this.progress=progress; }
    @GetMapping
    public LearningProgressService.View view(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
            @RequestParam(required=false) UUID sourceInterviewId) {
        return progress.view(principal.userId(),sourceInterviewId,page,size);
    }
}
