package com.projectardor.jobsearch.discovery.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobPageParserTests {
    private final JobPageParser parser = new JobPageParser();

    @Test void readsOnlyFactsDeclaredInJobPostingJsonLd() {
        String html = """
                <html><head><title>ignored</title>
                <script type="application/ld+json">
                {"@context":"https://schema.org","@type":"JobPosting",
                 "title":"Senior Backend Engineer",
                 "hiringOrganization":{"@type":"Organization","name":"Acme"},
                 "jobLocation":{"@type":"Place","address":{"addressLocality":"Beijing",
                   "addressRegion":"Beijing","addressCountry":"CN"}},
                 "baseSalary":{"@type":"MonetaryAmount","currency":"CNY",
                   "value":{"@type":"QuantitativeValue","minValue":30000,"maxValue":45000,"unitText":"MONTH"}},
                 "experienceRequirements":{"monthsOfExperience":24},
                 "validThrough":"2026-12-31"}
                </script></head><body></body></html>
                """;
        var job = parser.parse(html);
        assertThat(job.company()).isEqualTo("Acme");
        assertThat(job.title()).isEqualTo("Senior Backend Engineer");
        assertThat(job.location()).isEqualTo("Beijing, CN");
        assertThat(job.salaryText()).isEqualTo("CNY 30000–45000 MONTH");
        assertThat(job.experienceText()).isEqualTo("至少 24 个月经验");
        assertThat(job.deadlineText()).isEqualTo("2026-12-31");
    }

    @Test void findsPostingInsideGraphAndToleratesMalformedBlocks() {
        String html = """
                <script type="application/ld+json">{broken</script>
                <script type="application/ld+json">
                {"@context":"https://schema.org","@graph":[
                  {"@type":"Organization","name":"Acme"},
                  {"@type":["JobPosting"],"title":"Data Analyst",
                   "hiringOrganization":{"name":"Acme Data"},"jobLocation":[{"address":{"addressLocality":"Shanghai"}}]}
                ]}
                </script>
                """;
        var job = parser.parse(html);
        assertThat(job.company()).isEqualTo("Acme Data");
        assertThat(job.title()).isEqualTo("Data Analyst");
        assertThat(job.location()).isEqualTo("Shanghai");
        assertThat(job.salaryText()).isNull();
        assertThat(job.experienceText()).isNull();
        assertThat(job.deadlineText()).isNull();
    }

    @Test void metaFallbackKeepsTitleButNeverInventsCompanySalaryOrDeadline() {
        String html = """
                <html><head><title>Backend Engineer - Acme Careers</title>
                <meta content="Acme" property="og:site_name">
                <meta property="og:title" content="Backend Engineer">
                </head></html>
                """;
        var job = parser.parse(html);
        assertThat(job.title()).isEqualTo("Backend Engineer");
        assertThat(job.company()).isNull();
        assertThat(job.salaryText()).isNull();
        assertThat(job.experienceText()).isNull();
        assertThat(job.deadlineText()).isNull();
    }

    @Test void emptyOrUnrelatedPagesProduceNoFacts() {
        assertThat(parser.parse("").title()).isNull();
        assertThat(parser.parse("<html><body>Nothing here</body></html>").title()).isNull();
    }
}
