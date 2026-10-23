package com.projectardor.assistant.tools.modules;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.projectardor.materials.knowledge.service.KnowledgeDocumentView;
import com.projectardor.materials.knowledge.service.KnowledgeService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/** Knowledge-specific adaptation; business rules stay in materials. */
@Component
public final class KnowledgeAgentToolProvider implements AgentToolProvider {
    private final KnowledgeService service;
    public KnowledgeAgentToolProvider(KnowledgeService service) { this.service = service; }
    @Override public Bound bind(AgentToolContext context) { return new Bound(service, context); }
    @Override public List<Class<?>> toolTypes() { return List.of(Bound.class); }

    public static final class Bound {
        private final KnowledgeService service;
        private final AgentToolContext context;
        private Bound(KnowledgeService service, AgentToolContext context) {
            this.service = service;
            this.context = context;
        }

        @Tool(name = "list_knowledge_documents", value = "列出当前用户知识库中的上传文档和联网资料；返回文档 UUID、来源与切块数量")
        public List<KnowledgeDocumentView> listKnowledgeDocuments() {
            return service.list(context.userId());
        }

        @Tool(name = "search_knowledge", value = "从当前用户知识库检索与问题最相关的片段，这是 RAG 的检索步骤。回答知识库问题或从知识库出题前必须调用")
        public String searchKnowledge(
                @P("要检索的问题或关键词，最多 400 个字符") String query,
                @P(value = "返回片段数，1 到 10；通常传 5", required = false) Integer limit) {
            return context.untrustedContent().apply("knowledge-search",
                    service.search(context.userId(), query, limit == null ? 5 : limit));
        }

        @Tool(name = "research_knowledge_from_web", value = "主动联网搜索一个主题，并把可核验的来源摘要保存到当前用户知识库。适合用户要求补充知识库，或当前知识库缺少需要长期复用的资料时；成功后必须告诉用户新增了哪些来源")
        public String researchKnowledgeFromWeb(@P("明确、可搜索的研究主题，最多 400 个字符") String query) {
            return context.untrustedContent().apply("web-research", service.researchFromWeb(context.userId(), query));
        }

        @Tool(name = "delete_knowledge_document", value = "永久删除当前用户指定的一份知识文档及其全部检索切片。仅在用户明确要求删除时使用；先调用 list_knowledge_documents 定位真实 UUID")
        public Map<String, Object> deleteKnowledgeDocument(@P("知识文档 UUID") String documentId) {
            UUID id;
            try { id = UUID.fromString(documentId); }
            catch (RuntimeException failure) { throw new IllegalArgumentException("知识文档 ID 格式不正确"); }
            var document = service.get(context.userId(), id);
            return context.proposals().propose("knowledge_document", id, document.title(),
                    "连同它的全部检索切片一起删除", "/api/knowledge/documents/" + id);
        }
    }
}
