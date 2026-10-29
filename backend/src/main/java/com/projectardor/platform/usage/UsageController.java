package com.projectardor.platform.usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
public class UsageController {
    private final QuotaService quotas;
    private final UsagePolicyRepository policies;
    private final MembershipService memberships;
    private final MembershipPlanService plans;
    private final RedemptionService redemptions;
    private final UsagePackService packs;

    public UsageController(QuotaService quotas, UsagePolicyRepository policies, MembershipService memberships,
            MembershipPlanService plans, RedemptionService redemptions, UsagePackService packs) {
        this.quotas = quotas;
        this.policies = policies;
        this.memberships = memberships;
        this.plans = plans;
        this.redemptions = redemptions;
        this.packs = packs;
    }

    @GetMapping("/api/usage")
    public QuotaService.UsageSnapshot mine(@AuthenticationPrincipal ArdorPrincipal principal) {
        return quotas.snapshot(principal.userId());
    }

    @GetMapping("/api/plans")
    public List<MembershipPlanService.Plan> plans() {
        return plans.list();
    }

    @PostMapping("/api/usage/redeem")
    public RedemptionService.Redeemed redeem(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody RedeemRequest request) {
        return redemptions.redeem(principal.userId(), request.code());
    }

    @PostMapping("/api/usage/pack-codes/redeem")
    public UsagePackService.Redeemed redeemPack(@AuthenticationPrincipal ArdorPrincipal principal,
            @Valid @RequestBody RedeemRequest request) {
        return packs.redeem(principal.userId(), request.code());
    }

    @GetMapping("/api/admin/usage-policies")
    public List<UsagePolicy> policies() {
        return policies.list();
    }

    @PutMapping("/api/admin/usage-policies/{feature}")
    public UsagePolicy updatePolicy(@PathVariable UsageFeature feature,
            @Valid @RequestBody PolicyUpdate request) {
        return policies.update(feature, request.freeMonthlyLimit(), request.plusMonthlyLimit(),
                request.proMonthlyLimit(), request.freeWeeklyLimit(), request.plusWeeklyLimit(),
                request.proWeeklyLimit(), request.userMinuteLimit(), request.globalMinuteLimit());
    }

    @GetMapping("/api/admin/plans")
    public List<MembershipPlanService.Plan> allPlans() {
        return plans.list();
    }

    @PutMapping("/api/admin/plans/{tier}")
    public MembershipPlanService.Plan updatePlan(@PathVariable MembershipTier tier,
            @Valid @RequestBody PlanUpdateRequest request) {
        return plans.update(tier, new MembershipPlanService.PlanUpdate(
                request.priceCents(), request.tokenMonthlyLimit(), request.paymentEnabled()));
    }

    @PutMapping("/api/admin/users/{userId}/membership")
    public MembershipResponse updateMembership(@PathVariable UUID userId,
            @Valid @RequestBody MembershipUpdate request) {
        return new MembershipResponse(userId, memberships.setTier(userId, request.tier()));
    }

    @GetMapping("/api/admin/redemption-codes")
    public List<RedemptionService.CodeView> redemptionCodes() {
        return redemptions.list();
    }

    @PostMapping("/api/admin/redemption-codes")
    public RedemptionService.GeneratedCode generateRedemptionCode(
            @Valid @RequestBody GenerateCodeRequest request) {
        return redemptions.generate(request.planTier(), request.durationDays(), request.maxUses(),
                request.expiresAt(), request.note());
    }

    @GetMapping("/api/admin/usage-packs")
    public List<UsagePackService.Pack> packs() {
        return packs.list();
    }

    @GetMapping("/api/admin/usage-pack-codes")
    public List<UsagePackService.CodeView> packCodes() {
        return packs.listCodes();
    }

    @PostMapping("/api/admin/usage-pack-codes")
    public UsagePackService.GeneratedCode generatePackCode(@Valid @RequestBody GeneratePackCodeRequest request) {
        return packs.generate(request.packCode(), request.maxUses(), request.expiresAt(), request.note());
    }

    public record RedeemRequest(@NotBlank @Size(max = 80) String code) {}

    public record PolicyUpdate(@Min(0) int freeMonthlyLimit, @Min(0) int plusMonthlyLimit,
            @Min(0) int proMonthlyLimit, @Min(0) int freeWeeklyLimit, @Min(0) int plusWeeklyLimit,
            @Min(0) int proWeeklyLimit, @Min(1) int userMinuteLimit, @Min(1) int globalMinuteLimit) {}

    public record PlanUpdateRequest(@Min(0) int priceCents, @Min(0) long tokenMonthlyLimit,
            boolean paymentEnabled) {}

    public record MembershipUpdate(@NotNull MembershipTier tier) {}
    public record MembershipResponse(UUID userId, MembershipTier tier) {}

    public record GenerateCodeRequest(@NotBlank String planTier, @Min(1) @Max(3650) int durationDays,
            @Min(1) @Max(10000) int maxUses, Instant expiresAt, @Size(max = 200) String note) {}

    public record GeneratePackCodeRequest(@NotBlank String packCode, @Min(1) @Max(10000) int maxUses,
            Instant expiresAt, @Size(max = 200) String note) {}
}
