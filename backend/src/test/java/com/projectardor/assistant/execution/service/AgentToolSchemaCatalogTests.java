package com.projectardor.assistant.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.projectardor.assistant.tools.AgentToolSchemaCatalog;
import com.projectardor.assistant.tools.modules.KnowledgeAgentToolProvider;
import com.projectardor.materials.knowledge.service.KnowledgeService;
import com.projectardor.assistant.tools.modules.LearningAgentToolProvider;
import com.projectardor.learning.plan.service.LearningPlanService;

class AgentToolSchemaCatalogTests {
    @Test
    void optionalParametersRemainOptionalWithoutBindingOrExecutingTools() {
        var business = mock(LearningPlanService.class);
        var schemas = new AgentToolSchemaCatalog(List.of(new LearningAgentToolProvider(business))).schemas();
        var update = (Map<?, ?>) schemas.get("update_learning_plan");
        assertThat(update.get("required")).isEqualTo(List.of("planId"));
        assertThat(((Map<?, ?>) update.get("properties")).keySet())
                .isEqualTo(java.util.Set.of("planId", "reason", "scheduledAt"));
        assertThat(schemas).containsKey("get_profile");
        var create = (Map<?, ?>) schemas.get("create_learning_plan");
        assertThat(create.get("required")).isEqualTo(List.of("concept"));
        assertThat(((Map<?, ?>) create.get("properties")).keySet()).isEqualTo(java.util.Set.of(
                "concept", "reason", "scheduledAt", "sourceType", "sourceId", "originKind", "targetInterviewId", "targetRoundId"));
        org.mockito.Mockito.verifyNoInteractions(business);
    }

    @Test
    void includesModularToolsAndTheirOptionalParametersWithoutBusinessCalls() {
        var business = mock(KnowledgeService.class);
        var schemas = new AgentToolSchemaCatalog(List.of(new KnowledgeAgentToolProvider(business))).schemas();
        assertThat(schemas).containsKeys("list_knowledge_documents", "search_knowledge", "delete_knowledge_document");
        var search = (Map<?, ?>) schemas.get("search_knowledge");
        assertThat(search.get("required")).isEqualTo(List.of("query"));
        org.mockito.Mockito.verifyNoInteractions(business);
    }
}
