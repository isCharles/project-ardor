package com.projectardor.agent.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.projectardor.common.web.ResourceNotFoundException;

/** Live, single-use grants. A restart loses grants rather than replaying privileged work. */
@Service
public class AgentToolApprovals {
    private final ConcurrentHashMap<RunKey, Run> runs = new ConcurrentHashMap<>();
    private final Duration timeout;
    public AgentToolApprovals() { this(Duration.ofMinutes(2)); }
    AgentToolApprovals(Duration timeout) { this.timeout = timeout; }

    public void begin(UUID userId, UUID requestId) {
        if (requestId != null) runs.putIfAbsent(new RunKey(userId, requestId), new Run());
    }

    public boolean await(UUID userId, UUID requestId, String tool, String arguments) {
        Run run = requestId == null ? null : runs.get(new RunKey(userId, requestId));
        if (run == null || arguments != null && arguments.length() > 256_000) return false;
        Approval approval;
        synchronized (run) {
            if (run.closed || run.denied || run.approvals.size() >= 32) return false;
            approval = new Approval(UUID.randomUUID(), tool, arguments == null ? "{}" : arguments,
                    Instant.now().plus(timeout));
            run.approvals.add(approval);
        }
        try {
            boolean allowed = approval.decision.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            synchronized (run) {
                if (!allowed || run.closed || run.denied || !Instant.now().isBefore(approval.expiresAt)) {
                    if (approval.status.equals("PENDING") || approval.status.equals("APPROVED")) approval.status = "EXPIRED";
                    run.denied = true;
                    return false;
                }
                if (!approval.status.equals("APPROVED")) return false;
                approval.status = "CONSUMED";
                return true;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            expire(run, approval);
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            expire(run, approval);
            return false;
        }
    }

    public List<View> list(UUID userId, UUID requestId) {
        Run run = runs.get(new RunKey(userId, requestId));
        if (run == null) return List.of();
        synchronized (run) {
            return run.approvals.stream().map(approval -> {
                if (approval.status.equals("PENDING") && !Instant.now().isBefore(approval.expiresAt)) expire(run, approval);
                return new View(approval.id, approval.tool, approval.arguments, approval.status, approval.expiresAt);
            }).toList();
        }
    }

    public void decide(UUID userId, UUID requestId, UUID approvalId, boolean allow) {
        Run run = runs.get(new RunKey(userId, requestId));
        if (run == null) throw missing();
        synchronized (run) {
            Approval approval = run.approvals.stream().filter(item -> item.id.equals(approvalId)).findFirst().orElseThrow(AgentToolApprovals::missing);
            if (run.closed || run.denied || !approval.status.equals("PENDING") || !Instant.now().isBefore(approval.expiresAt)) {
                if (approval.status.equals("PENDING")) expire(run, approval);
                throw new IllegalStateException("该权限请求已结束或过期，不能重复授权");
            }
            approval.status = allow ? "APPROVED" : "DENIED";
            if (!allow) run.denied = true;
            approval.decision.complete(allow);
        }
    }

    public void close(UUID userId, UUID requestId) {
        if (requestId == null) return;
        Run run = runs.get(new RunKey(userId, requestId));
        if (run == null) return;
        synchronized (run) {
            run.closed = true;
            run.approvals.forEach(approval -> { if (approval.status.equals("PENDING")) expire(run, approval); });
        }
    }

    private void expire(Run run, Approval approval) {
        synchronized (run) {
            if (approval.status.equals("PENDING") || approval.status.equals("APPROVED")) approval.status = "EXPIRED";
            run.denied = true;
            approval.decision.complete(false);
        }
    }
    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(2));
        runs.forEach((key, run) -> { if (run.createdAt.isBefore(cutoff)) { close(key.userId, key.requestId); runs.remove(key, run); } });
    }
    private static ResourceNotFoundException missing() { return new ResourceNotFoundException("权限请求不存在"); }
    private record RunKey(UUID userId, UUID requestId) {}
    public record View(UUID id, String tool, String arguments, String status, Instant expiresAt) {}
    private static final class Run {
        final Instant createdAt = Instant.now();
        final java.util.ArrayList<Approval> approvals = new java.util.ArrayList<>();
        boolean closed;
        boolean denied;
    }
    private static final class Approval {
        final UUID id;
        final String tool;
        final String arguments;
        final Instant expiresAt;
        final CompletableFuture<Boolean> decision = new CompletableFuture<>();
        String status = "PENDING";
        Approval(UUID id, String tool, String arguments, Instant expiresAt) {
            this.id = id; this.tool = tool; this.arguments = arguments; this.expiresAt = expiresAt;
        }
    }
}
