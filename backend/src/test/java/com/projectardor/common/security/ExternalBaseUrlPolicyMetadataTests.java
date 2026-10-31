package com.projectardor.common.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExternalBaseUrlPolicyMetadataTests {

    private final ExternalBaseUrlPolicy policy = new ExternalBaseUrlPolicy(false);

    @Test void cloudMetadataAndPrivateLiteralsAreRejectedWithoutDnsLookup() {
        for (String host : new String[]{
                "http://127.0.0.1/job",
                "http://10.0.0.1/job",
                "http://192.168.1.10/job",
                "http://169.254.169.254/latest/meta-data/",
                "http://100.100.100.200/latest/meta-data/",
                "http://metadata.google.internal/computeMetadata/v1/"}) {
            assertThatThrownBy(() -> policy.normalizeAndValidate(host))
                    .as(host)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void userInfoAndNonHttpSchemesStayRejected() {
        assertThatThrownBy(() -> policy.normalizeAndValidate("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.normalizeAndValidate("https://user:pass@example.com/job"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
