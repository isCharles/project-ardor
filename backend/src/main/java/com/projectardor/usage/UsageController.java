package com.projectardor.usage;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.auth.security.ArdorPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@RestController
public class UsageController {
    private final QuotaService quotas;
    private final UsagePolicyRepository policies;
    private final MembershipService memberships;

    public UsageController(QuotaService quotas, UsagePolicyRepository policies, MembershipService memberships) {
        this.quotas = quotas;
        this.policies = policies;
        this.memberships = memberships;
    }

    @GetMapping("/api/usage")
    public QuotaService.UsageSnapshot mine(@AuthenticationPrincipal ArdorPrincipal principal) {
        return quotas.snapshot(principal.userId());
    }

    @GetMapping("/api/admin/usage-policies")
    public java.util.List<UsagePolicy> policies() {
        return policies.list();
    }

    @PutMapping("/api/admin/usage-policies/{feature}")
    public UsagePolicy updatePolicy(@PathVariable UsageFeature feature,
            @Valid @RequestBody PolicyUpdate request) {
        return policies.update(feature, request.freeMonthlyLimit(), request.memberMonthlyLimit(),
                request.userMinuteLimit(), request.globalMinuteLimit());
    }

    @PutMapping("/api/admin/users/{userId}/membership")
    public MembershipResponse updateMembership(@PathVariable UUID userId,
            @Valid @RequestBody MembershipUpdate request) {
        return new MembershipResponse(userId, memberships.setTier(userId, request.tier()));
    }

    public record PolicyUpdate(@Min(0) int freeMonthlyLimit, @Min(0) int memberMonthlyLimit,
            @Min(1) int userMinuteLimit, @Min(1) int globalMinuteLimit) {}
    public record MembershipUpdate(@NotNull MembershipTier tier) {}
    public record MembershipResponse(UUID userId, MembershipTier tier) {}
}
