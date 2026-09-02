package com.projectardor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArdorApplicationTests {

    @Test
    void applicationEntryPointExists() {
        assertThat(ArdorApplication.class).isNotNull();
    }
}

