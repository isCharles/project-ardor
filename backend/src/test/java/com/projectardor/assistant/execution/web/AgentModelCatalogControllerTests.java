package com.projectardor.assistant.execution.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.execution.service.AgentModelCatalogService;
import com.projectardor.assistant.execution.service.AgentModelDescriptionService;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

class AgentModelCatalogControllerTests {

    private static final String FINGERPRINT = "0".repeat(64);
    private final UUID user = UUID.randomUUID();
    private final ArdorPrincipal principal = new ArdorPrincipal(user, "fixture@ardor.invalid", "unused", true, UserRole.USER);
    private final AgentModelCatalogService catalog = mock(AgentModelCatalogService.class);
    private final AgentModelDescriptionService descriptions = mock(AgentModelDescriptionService.class);
    private final AgentModelCatalogController controller = new AgentModelCatalogController(catalog, descriptions);

    @Test
    void describesAnAvailableModelFromTheUsersCatalog() {
        when(catalog.descriptionFingerprint(user, "example-model")).thenReturn(FINGERPRINT);
        when(descriptions.describe(user, "example-model", "zh", FINGERPRINT)).thenReturn("高性价比模型");

        var response = controller.description(principal,
                new AgentModelCatalogController.DescriptionRequest("example-model", "zh"));

        assertThat(response.description()).isEqualTo("高性价比模型");
    }

    @Test
    void rejectsArbitraryAndUnavailableModelsBeforeResearch() {
        when(catalog.descriptionFingerprint(user, "invented-model"))
                .thenThrow(new IllegalArgumentException("未知的模型选项"));
        when(catalog.descriptionFingerprint(user, "disabled-model"))
                .thenThrow(new IllegalArgumentException("未知的模型选项"));

        assertThatThrownBy(() -> controller.description(principal,
                new AgentModelCatalogController.DescriptionRequest("invented-model", "en")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.description(principal,
                new AgentModelCatalogController.DescriptionRequest("disabled-model", "en")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(descriptions);
    }
}
