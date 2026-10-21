package com.projectardor.jobsearch.interview.web;

import jakarta.validation.constraints.Size;

/** Null fields are left unchanged; status carries an explicit lifecycle move. */
public record OpportunityUpdateRequest(
        @Size(max = 160) String company,
        @Size(max = 160) String targetRole,
        @Size(max = 80) String platform,
        @Size(max = 50000) String jdContent,
        @Size(max = 50000) String inviteContent,
        @Size(max = 32) String status) {
    public com.projectardor.jobsearch.interview.service.InterviewProcessCommands.Update command() {
        return new com.projectardor.jobsearch.interview.service.InterviewProcessCommands.Update(company,targetRole,platform,jdContent,inviteContent,status);
    }
}
