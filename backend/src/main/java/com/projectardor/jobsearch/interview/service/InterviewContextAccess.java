package com.projectardor.jobsearch.interview.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.projectardor.common.web.ResourceNotFoundException;
import com.projectardor.jobsearch.interview.repository.JobOpportunityRepository;
import com.projectardor.jobsearch.interview.repository.OpportunityRoundRepository;

/** Owned interview references for other domains; no repositories or Web DTOs leave this boundary. */
@Service
public class InterviewContextAccess {
    private final JobOpportunityRepository processes;
    private final OpportunityRoundRepository rounds;

    public InterviewContextAccess(JobOpportunityRepository processes, OpportunityRoundRepository rounds) {
        this.processes = processes;
        this.rounds = rounds;
    }

    @Transactional(readOnly = true)
    public Context require(UUID userId, UUID processId, UUID roundId) {
        if (processId == null) throw new IllegalArgumentException("请选择所属面试");
        if (processes.findByIdAndUserId(processId, userId).isEmpty()) throw missing();
        if (roundId != null) {
            var round = rounds.findByIdAndUserId(roundId, userId).orElseThrow(InterviewContextAccess::missing);
            if (!processId.equals(round.getOpportunityId())) throw missing();
        }
        return new Context(processId, roundId);
    }

    private static ResourceNotFoundException missing() {
        return new ResourceNotFoundException("面试流程不存在或轮次不属于该面试");
    }

    public record Context(UUID processId, UUID roundId) {}
}
