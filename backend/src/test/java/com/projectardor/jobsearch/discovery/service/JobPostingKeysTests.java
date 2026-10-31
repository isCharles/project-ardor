package com.projectardor.jobsearch.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JobPostingKeysTests {

    @Test void trackingParametersSchemeAndTrailingSlashDoNotCreateDuplicates() {
        String plain = JobPostingKeys.canonicalKey("https://jobs.example.com/roles/backend-engineer");
        assertThat(JobPostingKeys.canonicalKey("http://jobs.example.com/roles/backend-engineer/")).isEqualTo(plain);
        assertThat(JobPostingKeys.canonicalKey(
                "https://jobs.example.com/roles/backend-engineer?utm_source=x&gclid=1&ref=feed")).isEqualTo(plain);
        assertThat(JobPostingKeys.canonicalKey(
                "https://jobs.example.com/roles/backend-engineer?team=infra&utm_medium=email"))
                .isNotEqualTo(plain);
    }

    @Test void atsPostingIdsMergeAcrossHostsAndTrackingVariants() {
        assertThat(JobPostingKeys.canonicalKey("https://boards.greenhouse.io/acme/jobs/1234567"))
                .isEqualTo("greenhouse:1234567");
        assertThat(JobPostingKeys.canonicalKey(
                "https://job-boards.greenhouse.io/acme/jobs/1234567?gh_src=abc"))
                .isEqualTo("greenhouse:1234567");
        String leverId = "3f0c2b4e-6a1d-4f2b-9c3d-2a1b0c9d8e7f";
        assertThat(JobPostingKeys.canonicalKey("https://jobs.lever.co/acme/" + leverId))
                .isEqualTo("lever:" + leverId);
        assertThat(JobPostingKeys.canonicalKey("https://jobs.ashbyhq.com/acme/" + leverId + "?ashby_jid=x"))
                .isEqualTo("ashby:" + leverId);
        assertThat(JobPostingKeys.canonicalKey("https://acme.wd3.myworkdayjobs.com/en-US/jobs/job/Backend_R123456"))
                .isEqualTo("workday:acme.wd3.myworkdayjobs.com:R123456");
    }

    @Test void companyAndTitleAloneNeverMergeDifferentPosts() {
        String first = JobPostingKeys.canonicalKey("https://jobs.example.com/postings/1");
        String second = JobPostingKeys.canonicalKey("https://jobs.example.com/postings/2");
        assertThat(first).isNotEqualTo(second);
        assertThat(JobPostingKeys.canonicalKey("https://another.example.com/postings/1")).isNotEqualTo(first);
    }

    @Test void employerAndAtsLinksRankAboveAggregators() {
        assertThat(JobPostingKeys.sourcePriority("https://jobs.lever.co/acme/abc")).isZero();
        assertThat(JobPostingKeys.sourcePriority("https://acme.example.com/careers/1")).isEqualTo(1);
        assertThat(JobPostingKeys.sourcePriority("https://www.linkedin.com/jobs/view/1")).isEqualTo(2);
        assertThat(JobPostingKeys.sourceHost("https://Jobs.Example.com/a")).isEqualTo("jobs.example.com");
    }

    @Test void unsafeOrMalformedUrlsAreRejected() {
        assertThatThrownBy(() -> JobPostingKeys.canonicalKey("javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobPostingKeys.canonicalKey("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobPostingKeys.canonicalKey("https://user:secret@example.com/job"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobPostingKeys.canonicalKey("not a url"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
