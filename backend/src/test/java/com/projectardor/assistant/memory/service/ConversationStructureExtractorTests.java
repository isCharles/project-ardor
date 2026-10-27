package com.projectardor.assistant.memory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.assistant.conversation.domain.ConversationMessage;

class ConversationStructureExtractorTests {
    private final ConversationStructureExtractor extractor = new ConversationStructureExtractor();

    @Test
    void extractsUserGoalsFromNaturalLanguage() {
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID();
        var messages = List.of(
                ConversationMessage.user(user, conversation, "我想学习Java并发编程"),
                ConversationMessage.assistant(user, conversation, "好的，我来帮你"),
                ConversationMessage.user(user, conversation, "我需要了解synchronized和ReentrantLock的区别"));

        var result = extractor.extract(messages);

        assertThat(result.goals()).isNotEmpty();
        assertThat(result.goals().stream().anyMatch(g -> g.contains("Java") || g.contains("并发"))).isTrue();
    }

    @Test
    void extractsDecisionsFromConversation() {
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID();
        var messages = List.of(
                ConversationMessage.user(user, conversation, "我们用PostgreSQL吧"),
                ConversationMessage.assistant(user, conversation, "好的，确定使用PostgreSQL"),
                ConversationMessage.user(user, conversation, "决定采用Redis做缓存"));

        var result = extractor.extract(messages);

        assertThat(result.decisions()).isNotEmpty();
        assertThat(result.decisions().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void extractsUserCorrections() {
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID();
        var messages = List.of(
                ConversationMessage.assistant(user, conversation, "这个函数返回null"),
                ConversationMessage.user(user, conversation, "不对，应该返回空列表"),
                ConversationMessage.user(user, conversation, "其实这个参数应该是可选的"));

        var result = extractor.extract(messages);

        assertThat(result.corrections()).isNotEmpty();
        assertThat(result.corrections().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void extractsUuidEntitiesFromMessages() {
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID();
        String entity1 = UUID.randomUUID().toString();
        String entity2 = UUID.randomUUID().toString();
        var messages = List.of(
                ConversationMessage.user(user, conversation, "请查看对话 " + entity1),
                ConversationMessage.assistant(user, conversation, "好的，已找到 " + entity2));

        var result = extractor.extract(messages);

        assertThat(result.entities()).isNotEmpty();
        assertThat(result.entities().stream().map(StructuredSummary.Entity::id))
                .containsExactlyInAnyOrder(entity1, entity2);
    }

    @Test
    void extractsUnresolvedQuestions() {
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID();
        var messages = List.of(
                ConversationMessage.user(user, conversation, "为什么这个测试失败了？"),
                ConversationMessage.assistant(user, conversation, "让我检查一下"),
                ConversationMessage.user(user, conversation, "如何才能提高性能？"));

        var result = extractor.extract(messages);

        assertThat(result.unresolved()).isNotEmpty();
        assertThat(result.unresolved().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void deduplicatesRepeatedPatterns() {
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID();
        var messages = List.of(
                ConversationMessage.user(user, conversation, "我想学习Java"),
                ConversationMessage.user(user, conversation, "我想学习Java"),
                ConversationMessage.user(user, conversation, "我想学习Java"));

        var result = extractor.extract(messages);

        assertThat(result.goals()).hasSize(1);
    }

    @Test
    void extractionResultReportsWhetherStructureWasFound() {
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID();
        var empty = extractor.extract(List.of(
                ConversationMessage.user(user, conversation, "好的")));
        assertThat(empty.hasExtractedStructure()).isFalse();

        var structured = extractor.extract(List.of(
                ConversationMessage.user(user, conversation, "我想学习编程")));
        assertThat(structured.hasExtractedStructure()).isTrue();
    }

    @Test
    void buildsExtractionPromptWithIdentifiedElements() {
        UUID user = UUID.randomUUID(), conversation = UUID.randomUUID();
        var messages = List.of(
                ConversationMessage.user(user, conversation, "我想学习Spring Boot"),
                ConversationMessage.user(user, conversation, "决定使用MySQL"));

        var result = extractor.extract(messages);
        String prompt = extractor.buildExtractionPrompt(result);

        assertThat(prompt).contains("用户目标");
        assertThat(prompt).contains("已做决定");
    }
}
