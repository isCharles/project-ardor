package com.projectardor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class ApplicationConfigYamlTests {

    @Test
    void mainApplicationYamlHasNoDuplicateKeys() throws Exception {
        Path file = Path.of("src/main/resources/application.yml");
        assertThat(file).exists();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream in = Files.newInputStream(file)) {
            assertThatCode(() -> new SafeConstructor(options).load(in)).doesNotThrowAnyException();
        }
    }
}
