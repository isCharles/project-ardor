package com.projectardor.assistant.execution.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.execution.service.AgentModelCatalogService;
import com.projectardor.assistant.execution.service.AgentModelDescriptionService;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;

class AgentModelCatalogControllerTests {

    private final UUID user = UUID.randomUUID();
    private final ArdorPrincipal principal = new ArdorPrincipal(user, "fixture@ardor.invalid", "unused", true, UserRole.USER);
    private final AgentModelCatalogService catalog = mock(AgentModelCatalogService.class);
    private final AgentModelDescriptionService descriptions = mock(AgentModelDescriptionService.class);
    private final AgentModelCatalogController controller = new AgentModelCatalogController(catalog, descriptions);

    @Test
    void describesAnAvailableModelFromTheUsersCatalog() {
        when(catalog.options(user)).thenReturn(List.of(option("example-model", true)));
        when(descriptions.describe(user, "example-model", "zh")).thenReturn("高性价比模型");

        var response = controller.description(principal,
                new AgentModelCatalogController.DescriptionRequest("example-model", "zh"));

        assertThat(response.description()).isEqualTo("高性价比模型");
    }

    @Test
    void rejectsArbitraryAndUnavailableModelsBeforeResearch() {
        when(catalog.options(user)).thenReturn(List.of(option("disabled-model", false)));

        assertThatThrownBy(() -> controller.description(principal,
                new AgentModelCatalogController.DescriptionRequest("invented-model", "en")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.description(principal,
                new AgentModelCatalogController.DescriptionRequest("disabled-model", "en")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(descriptions);
    }

    private static AgentModelCatalogService.Option option(String model, boolean available) {
        return new AgentModelCatalogService.Option("FALLBACK:" + model, model, "OPENAI_COMPATIBLE",
                available, true, List.of());
    }
}
