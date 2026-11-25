package com.projectardor.assistant.execution.service;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Service;

import com.projectardor.assistant.tools.CareerAgentTools.PendingConfirmation;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Live deletion gates. Only an authenticated business DELETE may release a gate as deleted. */
@Service
public class AgentDeletionConfirmations {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    private final ConcurrentHashMap<Key, Pending> pending = new ConcurrentHashMap<>();
    private final Duration timeout;

    public AgentDeletionConfirmations() { this(DEFAULT_TIMEOUT); }
    AgentDeletionConfirmations(Duration timeout) { this.timeout = timeout; }

    public boolean await(UUID userId, UUID runId, PendingConfirmation confirmation) {
        return await(userId, runId, confirmation, () -> {});
    }

    public boolean await(UUID userId, UUID runId, PendingConfirmation confirmation, Runnable onRegistered) {
        if (userId == null || runId == null || confirmation == null || confirmation.proposalId() == null) return false;
        Key key = new Key(userId, runId, confirmation.proposalId());
        Pending gate = new Pending(confirmation);
        if (pending.putIfAbsent(key, gate) != null) return false;
        try {
            onRegistered.run();
            return gate.decision.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            dismiss(userId, runId, confirmation.proposalId());
            return false;
        } catch (RuntimeException | java.util.concurrent.ExecutionException | TimeoutException exception) {
            dismiss(userId, runId, confirmation.proposalId());
            if (exception instanceof RuntimeException runtime) throw runtime;
            return false;
        }
    }

    public void dismiss(UUID userId, UUID runId, UUID proposalId) {
        Pending gate = pending.remove(new Key(userId, runId, proposalId));
        if (gate != null) gate.decision.complete(false);
    }

    public void close(UUID userId, UUID runId) {
        pending.forEach((key, gate) -> {
            if (key.userId().equals(userId) && key.runId().equals(runId)) dismiss(userId, runId, key.proposalId());
        });
    }

    public void completeDeletion(UUID userId, String requestUri, Set<UUID> targetIds) {
        pending.entrySet().stream()
                .filter(entry -> entry.getKey().userId().equals(userId)
                        && matches(entry.getValue().confirmation, requestUri, targetIds))
                .forEach(entry -> {
                    if (pending.remove(entry.getKey(), entry.getValue())) entry.getValue().decision.complete(true);
                });
    }

    private static boolean matches(PendingConfirmation confirmation, String requestUri, Set<UUID> targetIds) {
        if (!confirmation.endpoint().equals(requestUri)) return false;
        if (confirmation.targetIds() != null) {
            return targetIds != null && targetIds.size() == confirmation.targetIds().size()
                    && targetIds.containsAll(confirmation.targetIds());
        }
        if (confirmation.targetId() == null) return true;
        if (targetIds != null) return targetIds.size() == 1 && targetIds.contains(confirmation.targetId());
        int separator = requestUri.lastIndexOf('/');
        if (separator < 0) return false;
        try {
            return UUID.fromString(requestUri.substring(separator + 1)).equals(confirmation.targetId());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Bridges successful authenticated DELETEs to their owner/run/proposal-scoped live gates. */
    public static class CompletionFilter extends org.springframework.web.filter.OncePerRequestFilter {
        private final AgentDeletionConfirmations confirmations;
        private final ObjectMapper mapper;

        public CompletionFilter(AgentDeletionConfirmations confirmations, ObjectMapper mapper) {
            this.confirmations = confirmations;
            this.mapper = mapper;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                jakarta.servlet.FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
            if (!"DELETE".equalsIgnoreCase(request.getMethod())) {
                chain.doFilter(request, response);
                return;
            }
            var cached = new org.springframework.web.util.ContentCachingRequestWrapper(request, 256_000);
            var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            ArdorPrincipal principal = authentication != null && authentication.getPrincipal() instanceof ArdorPrincipal owner
                    ? owner : null;
            chain.doFilter(cached, response);
            if (response.getStatus() < 200 || response.getStatus() >= 300 || principal == null) return;
            byte[] body = cached.getContentAsByteArray();
            Set<UUID> ids = null;
            if (body.length > 0) {
                try {
                    JsonNode json = mapper.readTree(body);
                    JsonNode values = json.path("ids");
                    if (values.isArray()) {
                        ids = new java.util.HashSet<>();
                        for (JsonNode value : values) ids.add(UUID.fromString(value.asText()));
                        if (ids.size() != values.size()) return;
                    }
                } catch (RuntimeException exception) {
                    return;
                }
            }
            confirmations.completeDeletion(principal.userId(), request.getRequestURI(), ids);
        }
    }

    private record Key(UUID userId, UUID runId, UUID proposalId) {}
    private static final class Pending {
        final PendingConfirmation confirmation;
        final CompletableFuture<Boolean> decision = new CompletableFuture<>();
        Pending(PendingConfirmation confirmation) { this.confirmation = confirmation; }
    }
}
