package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.projectardor.assistant.tools.CareerAgentTools.PendingConfirmation;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

import tools.jackson.databind.json.JsonMapper;

class AgentDeletionConfirmationsTests {
    private final UUID user = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final AgentDeletionConfirmations confirmations = new AgentDeletionConfirmations(java.time.Duration.ofSeconds(2));

    @AfterEach
    void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    void runWaitsForItsOwnSuccessfulBusinessDeletion() {
        PendingConfirmation proposal = proposal(target, null);
        CompletableFuture<Boolean> waiting = start(proposal);

        assertBlocked(waiting);
        confirmations.completeDeletion(UUID.randomUUID(), proposal.endpoint(), null);
        assertBlocked(waiting);
        confirmations.completeDeletion(user, "/api/memory-cards/" + UUID.randomUUID(), null);
        assertBlocked(waiting);
        confirmations.completeDeletion(user, proposal.endpoint(), null);

        assertThat(waiting.join()).isTrue();
    }

    @Test
    void oneDeletionReleasesEveryMatchingWaitForTheSameOwner() {
        UUID otherRun = UUID.randomUUID();
        PendingConfirmation proposal = proposal(target, null);
        CompletableFuture<Boolean> first = start(proposal);
        java.util.concurrent.CountDownLatch secondRegistered = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(
                () -> confirmations.await(user, otherRun, proposal, secondRegistered::countDown));
        try {
            assertThat(secondRegistered.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }

        confirmations.completeDeletion(user, proposal.endpoint(), null);

        assertThat(first.join()).isTrue();
        assertThat(second.join()).isTrue();
    }

    @Test
    void snapshotDeletionRequiresTheExactProposedIds() {
        UUID second = UUID.randomUUID();
        PendingConfirmation proposal = proposal(null, List.of(target, second));
        CompletableFuture<Boolean> waiting = start(proposal);

        confirmations.completeDeletion(user, proposal.endpoint(), Set.of(target));
        assertBlocked(waiting);
        confirmations.completeDeletion(user, proposal.endpoint(), Set.of(target, second, UUID.randomUUID()));
        assertBlocked(waiting);
        confirmations.completeDeletion(user, proposal.endpoint(), Set.of(second, target));

        assertThat(waiting.join()).isTrue();
    }

    @Test
    void deletionCanCompleteAsSoonAsTheCardIsRegistered() {
        PendingConfirmation proposal = proposal(target, null);

        boolean deleted = confirmations.await(user, run, proposal,
                () -> confirmations.completeDeletion(user, proposal.endpoint(), null));

        assertThat(deleted).isTrue();
    }

    @Test
    void keepAndRunCloseReleaseTheWaitWithoutClaimingDeletion() {
        CompletableFuture<Boolean> kept = start(proposal(target, null));
        confirmations.dismiss(user, run, keptProposalId);
        assertThat(kept.join()).isFalse();

        CompletableFuture<Boolean> closed = start(proposal(target, null));
        confirmations.close(user, run);
        assertThat(closed.join()).isFalse();
    }

    @Test
    void closedRunCannotRegisterALateDeletionWait() {
        PendingConfirmation proposal = proposal(target, null);
        AtomicBoolean active = new AtomicBoolean();
        AtomicBoolean emitted = new AtomicBoolean();
        confirmations.close(user, run);

        boolean deleted = confirmations.await(user, run, proposal, () -> emitted.set(true), active::get);

        assertThat(deleted).isFalse();
        assertThat(emitted).isFalse();
    }

    @Test
    void runStoppedDuringRegistrationDoesNotLeaveAWait() {
        PendingConfirmation proposal = proposal(target, null);
        AtomicBoolean active = new AtomicBoolean(true);

        boolean deleted = confirmations.await(user, run, proposal, () -> active.set(false), active::get);

        assertThat(deleted).isFalse();
    }

    @Test
    void pendingDeletionCardsAreScopedToTheirOwnerAndRun() throws Exception {
        PendingConfirmation proposal = proposal(target, null);
        java.util.concurrent.CountDownLatch registered = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(
                () -> confirmations.await(user, run, proposal, registered::countDown));
        try {
            assertThat(registered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(confirmations.pending(user, run)).containsExactly(proposal);
            assertThat(confirmations.pending(UUID.randomUUID(), run)).isEmpty();
            assertThat(confirmations.pending(user, UUID.randomUUID())).isEmpty();
        } finally {
            confirmations.dismiss(user, run, proposal.proposalId());
        }
        assertThat(waiting.join()).isFalse();
        assertThat(confirmations.pending(user, run)).isEmpty();
    }

    @Test
    void filterReleasesOnlyAfterAnAuthenticatedSuccessfulDelete() throws Exception {
        PendingConfirmation proposal = proposal(target, null);
        CompletableFuture<Boolean> waiting = start(proposal);
        authenticate();
        var filter = new AgentDeletionConfirmations.CompletionFilter(confirmations, JsonMapper.builder().build());

        var failedRequest = request(proposal.endpoint());
        filter.doFilter(failedRequest, new MockHttpServletResponse(), (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(500));
        assertBlocked(waiting);

        var request = request(proposal.endpoint());
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(204);
            SecurityContextHolder.clearContext();
        });
        assertThat(waiting.join()).isTrue();
    }

    private final UUID keptProposalId = UUID.randomUUID();

    private CompletableFuture<Boolean> start(PendingConfirmation proposal) {
        return CompletableFuture.supplyAsync(() -> confirmations.await(user, run, proposal));
    }

    private PendingConfirmation proposal(UUID targetId, List<UUID> targetIds) {
        return new PendingConfirmation(targetIds == null ? "memory_card" : "all_memory_cards", targetId,
                "Fixture", "Fixture detail", targetIds == null ? "/api/memory-cards/" + targetId : "/api/memory-cards/snapshot",
                null, targetIds, keptProposalId);
    }

    private static MockHttpServletRequest request(String uri) {
        var request = new MockHttpServletRequest("DELETE", uri);
        request.setRequestURI(uri);
        return request;
    }

    private void authenticate() {
        var principal = new ArdorPrincipal(user, "fixture@ardor.invalid", "unused", true, UserRole.USER);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private static void assertBlocked(CompletableFuture<Boolean> waiting) {
        assertThatThrownBy(() -> waiting.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    }
}
