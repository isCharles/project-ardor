package com.projectardor.assistant.memory.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.projectardor.assistant.conversation.domain.ConversationMessage;

/**
 * Extracts structured information from conversation messages to guide summarization.
 *
 * <p>This pre-processor identifies key elements that must be preserved in summaries:
 * user goals, decisions, corrections, entity references, and unresolved questions.
 * The extracted structure guides the summarization model and validates the output.</p>
 */
public class ConversationStructureExtractor {
    private static final Logger log = LoggerFactory.getLogger(ConversationStructureExtractor.class);

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private static final Pattern GOAL_PATTERN = Pattern.compile(
            "(我想|我需要|帮我|请|能不能|可以|目标|目的是|为了)([^。，！？]{2,50})");
    private static final Pattern DECISION_PATTERN = Pattern.compile(
            "(决定|确定|就用|选择|采用|同意|好的|可以)([^。，！？]{2,50})");
    private static final Pattern CORRECTION_PATTERN = Pattern.compile(
            "(不对|不是|错了|应该|其实|实际上|纠正|修改|改成|不要)([^。，！？]{2,50})");
    private static final Pattern QUESTION_PATTERN = Pattern.compile(
            "([^。，！？]{0,20})(\\?|？|怎么|如何|为什么|什么|哪|谁|几|多少)([^。，！？]{0,30})");

    public ExtractionResult extract(List<ConversationMessage> messages) {
        List<String> goals = new ArrayList<>();
        List<String> decisions = new ArrayList<>();
        List<String> corrections = new ArrayList<>();
        List<StructuredSummary.Entity> entities = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        Set<String> entityIds = new HashSet<>();

        for (ConversationMessage message : messages) {
            String content = message.getContent();
            if (content == null || content.isBlank()) continue;
            String role = message.getRole();

            if ("USER".equalsIgnoreCase(role)) {
                extractMatches(content, GOAL_PATTERN, goals);
                extractMatches(content, DECISION_PATTERN, decisions);
                extractMatches(content, CORRECTION_PATTERN, corrections);
                extractMatches(content, QUESTION_PATTERN, unresolved);
                extractUuids(content, entityIds);
            } else if ("ASSISTANT".equalsIgnoreCase(role)) {
                extractMatches(content, DECISION_PATTERN, decisions);
                extractUuids(content, entityIds);
            }
        }

        for (String id : entityIds) {
            entities.add(new StructuredSummary.Entity("id", id, null));
        }

        return new ExtractionResult(
                deduplicate(goals),
                deduplicate(decisions),
                deduplicate(corrections),
                entities,
                deduplicate(unresolved));
    }

    public String buildExtractionPrompt(ExtractionResult extraction) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请确保摘要包含以下已识别的关键信息：\n\n");

        if (!extraction.goals().isEmpty()) {
            prompt.append("用户目标：").append(String.join("；", extraction.goals())).append("\n");
        }
        if (!extraction.decisions().isEmpty()) {
            prompt.append("已做决定：").append(String.join("；", extraction.decisions())).append("\n");
        }
        if (!extraction.corrections().isEmpty()) {
            prompt.append("用户纠正：").append(String.join("；", extraction.corrections())).append("\n");
        }
        if (!extraction.entities().isEmpty()) {
            prompt.append("关键实体ID：");
            for (int i = 0; i < Math.min(5, extraction.entities().size()); i++) {
                if (i > 0) prompt.append("、");
                prompt.append(extraction.entities().get(i).id());
            }
            prompt.append("\n");
        }
        if (!extraction.unresolved().isEmpty()) {
            prompt.append("未解决问题：").append(String.join("；", extraction.unresolved)).append("\n");
        }

        return prompt.toString();
    }

    public String buildStructuredSummaryPrompt(String existingSummary, List<ConversationMessage> older,
            ExtractionResult extraction) {
        StringBuilder prompt = new StringBuilder("""
                你是 Ardor 的上下文压缩器。请把旧对话压缩成结构化摘要。

                输出格式要求（JSON）：
                {
                  "goals": ["用户目标1", "用户目标2"],
                  "decisions": ["已做决定1", "已做决定2"],
                  "corrections": ["用户纠正1"],
                  "entities": [{"type": "conversation", "id": "uuid", "description": "描述"}],
                  "unresolved": ["未解决问题1"],
                  "toolConclusions": [{"tool": "工具名", "conclusion": "关键结论"}],
                  "narrative": "整体概述，1-2句话"
                }

                只保留重要信息，不保留寒暄、重复内容、模型猜测；不编造。用中文输出。
                图片消息只记录“用户发送过图片”这一事实；摘要看不到图片内容，不得描述或猜测图片内容。
                确保JSON格式正确，可被解析。

                """);

        if (!extraction.goals().isEmpty() || !extraction.decisions().isEmpty() || !extraction.corrections().isEmpty()) {
            prompt.append("已识别的关键信息（必须保留）：\n");
            prompt.append(buildExtractionPrompt(extraction));
            prompt.append("\n");
        }

        prompt.append("已有摘要：\n");
        prompt.append(existingSummary.isBlank() ? "（无）" : existingSummary);
        prompt.append("\n\n需要并入摘要的旧对话：\n");

        int used = 0;
        int included = 0;
        for (ConversationMessage message : older) {
            String content = message.getContent() == null ? "" : message.getContent();
            String imageMarker = message.getImageIds().isEmpty() ? ""
                    : " [附带 " + message.getImageIds().size() + " 张图片，内容不可见]";
            if (included > 0 && used + content.length() + imageMarker.length() > 12_000) break;
            prompt.append(message.getRole()).append(": ").append(content).append(imageMarker).append('\n');
            used += content.length() + imageMarker.length();
            included++;
        }

        return prompt.toString();
    }

    private void extractMatches(String text, Pattern pattern, List<String> results) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String match = matcher.group(0).trim();
            if (match.length() >= 5 && match.length() <= 200) {
                results.add(match);
            }
        }
    }

    private void extractUuids(String text, Set<String> uuids) {
        Matcher matcher = UUID_PATTERN.matcher(text);
        while (matcher.find()) {
            String uuid = matcher.group();
            try {
                UUID.fromString(uuid);
                uuids.add(uuid);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private List<String> deduplicate(List<String> items) {
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String item : items) {
            if (seen.add(item)) {
                result.add(item);
            }
        }
        return result;
    }

    public record ExtractionResult(
            List<String> goals,
            List<String> decisions,
            List<String> corrections,
            List<StructuredSummary.Entity> entities,
            List<String> unresolved) {

        public boolean hasExtractedStructure() {
            return !goals.isEmpty() || !decisions.isEmpty() || !corrections.isEmpty()
                    || !entities.isEmpty() || !unresolved.isEmpty();
        }
    }
}
