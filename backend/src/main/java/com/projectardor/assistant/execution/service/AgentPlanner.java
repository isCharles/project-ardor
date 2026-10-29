package com.projectardor.assistant.execution.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.projectardor.assistant.tools.capability.ToolCatalog;
import com.projectardor.assistant.tools.capability.ToolModule;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Decides who may plan, what a step may touch, and whether a step is actually satisfied.
 *
 * <p>The gate is deliberately narrow: a plan is admitted only when the steps span at least two
 * business modules and every step names registered tools with a stated completion condition. A
 * single-module request — however many calls it takes — keeps using the ordinary bounded loop, so
 * ordinary chat pays nothing for this layer. Verification is decided from the recorded execution
 * outcome, never from the model's prose about it.
 */
@Service
public class AgentPlanner {

    static final int MIN_STEPS = 3;
    static final int MAX_STEPS = 6;
    static final int MAX_TOOLS_PER_STEP = 4;
    static final int MIN_MODULES = 2;
    static final int REVISION_CEILING = 2;
    static final int MODEL_CALL_CEILING = 12;
    private static final int TEXT_LIMIT = 200;
    private static final int VERIFY_VALUE_LIMIT = 200;
    private static final Set<String> VERIFY_OPS =
            Set.of("MIN_ITEMS", "MAX_ITEMS", "AT_LEAST", "EQUALS", "CONTAINS", "HAS_ID", "SUCCESS");
    private static final Set<String> SUCCESS_STATUSES =
            Set.of("OK", "SUCCESS", "CREATED", "UPDATED", "COMPLETED", "DONE", "ACCEPTED");
    /** Job execution states that are not a completed prerequisite. */
    private static final Set<String> PENDING_STATUSES =
            Set.of("QUEUED", "RUNNING", "PENDING", "PROCESSING");
    /** Hard failures that are never a successful resource terminal state. */
    private static final Set<String> FAILURE_STATUSES = Set.of("FAILED", "ERROR", "TIMEOUT");
    /** Result markers that mean the tool did not accomplish anything, mirrored from the wrapper. */
    private static final Set<String> REFUSALS = Set.of(
            "PERMISSION_NOT_GRANTED", "TOOL_NOT_PERMITTED", "DUPLICATE_WRITE_BLOCKED", "PLAN_SCOPE_REFUSED",
            "PLAN_REPLAN_REQUIRED");

    private final ObjectMapper objectMapper;

    public AgentPlanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Admission(List<AgentPlanStore.StepDraft> steps, String rejection) {
        public boolean accepted() {
            return rejection == null;
        }

        static Admission rejected(String reason) {
            return new Admission(List.of(), reason);
        }
    }

    /**
     * What the wrapper should do after one recorded tool outcome for the active plan. Results are the
     * actual tool payloads seen for this step; evidence is a verifier summary, never the model's
     * natural-language doneWhen.
     */
    public record StepOutcome(UUID stepId, int stepIndex, boolean verified, boolean failed,
            Map<String, String> results, String evidence, String guidance) {
        static final StepOutcome IGNORED = new StepOutcome(null, -1, false, false, Map.of(), null, null);

        public Set<String> toolsDone() {
            return results.keySet();
        }
    }

    public Admission admit(UUID userId, UUID requestId, UUID conversationId, String goal, String stepsJson,
            Optional<AgentPlanStore.Plan> existing) {
        if (goal == null || goal.isBlank()) return Admission.rejected("总目标不能为空");
        if (existing.isPresent() && existing.get().active() && !existing.get().hasFailedStep()) {
            return Admission.rejected("本轮已有进行中的计划；只有当前步骤执行失败后才可重规划，否则请按剩余步骤继续");
        }
        if (existing.isPresent() && existing.get().revision() >= REVISION_CEILING) {
            return Admission.rejected("重规划次数已达上限（" + REVISION_CEILING + " 次）；请停止重排，向用户说明未完成的部分和原因");
        }
        List<AgentPlanStore.StepDraft> drafts;
        try {
            drafts = parseSteps(stepsJson);
        } catch (IllegalArgumentException failure) {
            return Admission.rejected(failure.getMessage());
        }
        if (drafts.size() < MIN_STEPS || drafts.size() > MAX_STEPS) {
            return Admission.rejected("计划步骤数必须在 " + MIN_STEPS + " 到 " + MAX_STEPS + " 之间；不足时请直接执行");
        }
        EnumSet<ToolModule> modules = EnumSet.noneOf(ToolModule.class);
        for (AgentPlanStore.StepDraft draft : drafts) {
            for (String tool : draft.tools()) {
                ToolCatalog.Risk risk = ToolCatalog.describe(tool);
                if (risk == null) return Admission.rejected("未登记的工具：" + tool + "；计划只能使用已登记工具");
                modules.add(risk.module());
            }
        }
        if (modules.size() < MIN_MODULES) {
            return Admission.rejected("任务未跨模块，无需计划；按普通流程直接完成");
        }
        return new Admission(drafts, null);
    }

    private List<AgentPlanStore.StepDraft> parseSteps(String stepsJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(stepsJson == null ? "" : stepsJson);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("步骤必须是 JSON 数组：[{\"intent\":\"…\",\"doneWhen\":\"…\",\"tools\":[\"…\"],\"effect\":\"READ|WRITE\"}]");
        }
        if (!root.isArray() || root.isEmpty()) {
            throw new IllegalArgumentException("步骤必须是 JSON 数组，且至少一步");
        }
        List<AgentPlanStore.StepDraft> drafts = new ArrayList<>();
        for (JsonNode step : root) {
            String intent = text(step, "intent");
            String doneWhen = text(step, "doneWhen");
            String effect = text(step, "effect");
            List<String> tools = new ArrayList<>();
            JsonNode toolNodes = step.path("tools");
            if (toolNodes.isArray()) toolNodes.forEach(node -> tools.add(node.asText()));
            if (intent.isBlank() || intent.length() > TEXT_LIMIT) throw new IllegalArgumentException("每一步都要写清楚 intent（不超过 " + TEXT_LIMIT + " 字）");
            if (doneWhen.isBlank() || doneWhen.length() > TEXT_LIMIT) throw new IllegalArgumentException("每一步都要给出完成条件 doneWhen");
            if (tools.isEmpty() || tools.size() > MAX_TOOLS_PER_STEP) throw new IllegalArgumentException("每一步需要 1 到 " + MAX_TOOLS_PER_STEP + " 个已登记工具");
            if (!"READ".equals(effect) && !"WRITE".equals(effect)) throw new IllegalArgumentException("effect 只能是 READ 或 WRITE");
            AgentPlanStore.Verification verification = parseVerification(step, tools);
            drafts.add(new AgentPlanStore.StepDraft(intent, doneWhen, verification, List.copyOf(tools), effect));
        }
        return List.copyOf(drafts);
    }

    /**
     * A step is only accepted with a predicate that can be checked against a real tool result. The
     * doneWhen text remains a human description and is never used as proof.
     */
    private AgentPlanStore.Verification parseVerification(JsonNode step, List<String> tools) {
        JsonNode verify = step.path("verify");
        if (!verify.isObject()) {
            throw new IllegalArgumentException("每一步都要给出可核验的 verify 对象，例如 "
                    + "{\"tool\":\"list_interview_recaps\",\"path\":\"$\",\"op\":\"MIN_ITEMS\",\"value\":1}");
        }
        String tool = text(verify, "tool");
        String path = text(verify, "path");
        if (path.isBlank()) path = "$";
        String op = text(verify, "op").toUpperCase(Locale.ROOT);
        if (tool.isBlank() || !tools.contains(tool)) {
            throw new IllegalArgumentException("verify.tool 必须是本步骤 tools 中的一个：" + tool);
        }
        if (ToolCatalog.describe(tool) == null) throw new IllegalArgumentException("未登记的工具：" + tool);
        if (!VERIFY_OPS.contains(op)) {
            throw new IllegalArgumentException("verify.op 只支持 " + String.join("/", VERIFY_OPS));
        }
        if (path.length() > 200) throw new IllegalArgumentException("verify.path 过长");
        JsonNode valueNode = verify.path("value");
        String value = valueNode.isNumber() || valueNode.isBoolean() || valueNode.isTextual()
                ? valueNode.asText().strip() : null;
        boolean requiresValue = Set.of("MIN_ITEMS", "MAX_ITEMS", "AT_LEAST", "EQUALS", "CONTAINS").contains(op);
        if (requiresValue && (value == null || value.isBlank())) {
            throw new IllegalArgumentException("verify.op=" + op + " 需要 value");
        }
        if (value != null && value.length() > VERIFY_VALUE_LIMIT) {
            throw new IllegalArgumentException("verify.value 不能超过 " + VERIFY_VALUE_LIMIT + " 字符");
        }
        if (Set.of("MIN_ITEMS", "MAX_ITEMS", "AT_LEAST").contains(op) && !value.matches("\\d+")) {
            throw new IllegalArgumentException("verify.op=" + op + " 的 value 必须是非负整数");
        }
        return new AgentPlanStore.Verification(tool, path, op, value);
    }

    /**
     * A write the model wants to call now, judged against the current step only. Later steps wait:
     * a reschedule write cannot run before the conflict check that was declared first.
     */
    public Optional<String> refusal(AgentPlanStore.Plan plan, String tool) {
        if (plan == null || !plan.active()) return Optional.empty();
        Optional<AgentPlanStore.Step> current = plan.currentStep();
        if (current.isEmpty()) {
            return Optional.of(refusalJson("PLAN_SCOPE_REFUSED",
                    "计划的所有步骤都已完成，不能再发起新的写入；请向用户说明结果并结束本轮。"));
        }
        AgentPlanStore.Step step = current.get();
        if (step.failed()) {
            return Optional.of(refusalJson("PLAN_REPLAN_REQUIRED", "步骤 " + (step.index() + 1) + "「"
                    + step.intent() + "」已经失败；后续写入不能继续。请先调用 propose_plan 提交修订计划，再执行新的写入。"));
        }
        if (step.tools().contains(tool)) return Optional.empty();
        return Optional.of(refusalJson("PLAN_SCOPE_REFUSED", "该工具属于后续步骤。当前只能执行第 "
                + (step.index() + 1) + " 步「" + step.intent()
                + "」声明的工具；先完成当前步骤，不要提前执行后续写入。"));
    }

    private String refusalJson(String status, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("status", status, "retryable", false, "message", message));
        } catch (RuntimeException failure) {
            return "{\"status\":\"" + status + "\",\"retryable\":false}";
        }
    }

    /**
     * Attributes one recorded tool outcome to the current step. A step is verified only after every
     * declared tool ran and its verify predicate holds against the real result; a refused,
     * unexecuted, empty, or structurally unverifiable result fails the step instead.
     *
     * @param resultsThisRun the tool results this run already recorded for that step, without this call
     */
    public StepOutcome attribute(AgentPlanStore.Plan plan, String tool, boolean executed, String result,
            Map<String, String> resultsThisRun) {
        if (plan == null || !plan.active()) return StepOutcome.IGNORED;
        Optional<AgentPlanStore.Step> current = plan.currentStep();
        if (current.isEmpty()) return StepOutcome.IGNORED;
        AgentPlanStore.Step step = current.get();
        if (step.failed() || !step.tools().contains(tool)) return StepOutcome.IGNORED;
        Map<String, String> results = new LinkedHashMap<>(resultsThisRun);
        boolean refused = executed && result != null && REFUSALS.stream().anyMatch(result::contains);
        if (!executed || refused || result == null || result.isBlank()) {
            return new StepOutcome(step.id(), step.index(), false, true, Map.copyOf(results), null,
                    "步骤 " + (step.index() + 1) + "「" + step.intent() + "」未能完成："
                            + (refused ? "本次写入被拒绝" : "工具调用未执行或未返回结果")
                            + "。请如实说明未完成，不要改口声称成功；需要时重规划。");
        }
        results.put(tool, result);
        List<String> missing = step.tools().stream().filter(name -> !results.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            return new StepOutcome(step.id(), step.index(), false, false, Map.copyOf(results), null,
                    "步骤 " + (step.index() + 1) + "「" + step.intent() + "」已记录 " + tool
                            + "，还差：" + String.join("、", missing) + "。继续完成这些动作后该步骤才算核验通过。");
        }
        VerificationResult verification = verify(step, results);
        if (!verification.passed()) {
            return new StepOutcome(step.id(), step.index(), false, true, Map.copyOf(results),
                    verification.evidence(),
                    "步骤 " + (step.index() + 1) + "「" + step.intent() + "」的完成条件未被工具结果证实（"
                            + verification.reason() + "）。不要声称完成；请调用 propose_plan 重规划，或向用户说明未完成。");
        }
        return new StepOutcome(step.id(), step.index(), true, false, Map.copyOf(results),
                verification.evidence(),
                "步骤 " + (step.index() + 1) + "「" + step.intent() + "」已按工具结果核验通过，请继续下一步。");
    }

    private record VerificationResult(boolean passed, String evidence, String reason) {
    }

    private VerificationResult verify(AgentPlanStore.Step step, Map<String, String> results) {
        AgentPlanStore.Verification verification = step.verification();
        if (verification == null) {
            return new VerificationResult(false, null, "该步骤没有声明可核验的 verify");
        }
        String raw = results.get(verification.tool());
        if (raw == null) {
            return new VerificationResult(false, null, "缺少核验工具 " + verification.tool() + " 的结果");
        }
        String payload = unwrapUntrusted(raw);
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (RuntimeException failure) {
            // External search/knowledge tools may return text inside the untrusted envelope; CONTAINS
            // and EQUALS can still inspect that text without pretending it is structured JSON.
            root = objectMapper.getNodeFactory().stringNode(payload);
        }
        JsonNode node = at(root, verification.path());
        boolean passed = switch (verification.op()) {
            case "MIN_ITEMS" -> node != null && node.isArray() && longValue(verification.value()) >= 0
                    && node.size() >= longValue(verification.value());
            case "MAX_ITEMS" -> node != null && node.isArray() && longValue(verification.value()) >= 0
                    && node.size() <= longValue(verification.value());
            case "AT_LEAST" -> node != null && node.isNumber() && longValue(verification.value()) >= 0
                    && node.asLong() >= longValue(verification.value());
            case "EQUALS" -> node != null && node.asText().equals(verification.value());
            case "CONTAINS" -> contains(node, verification.value());
            case "HAS_ID" -> node != null && node.isObject() && hasId(node);
            case "SUCCESS" -> success(node);
            default -> false;
        };
        String evidence = "verify=" + verification.op() + " tool=" + verification.tool()
                + " path=" + verification.path() + " observed=" + summarize(node)
                + " sha256=" + sha(raw);
        return new VerificationResult(passed, evidence, passed ? null : "观测值 " + summarize(node) + " 不满足条件");
    }

    private static long longValue(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            return -1;
        }
    }

    private static boolean contains(JsonNode node, String value) {
        if (node == null || value == null) return false;
        if (node.isArray()) {
            for (JsonNode item : node) if (item.asText().contains(value)) return true;
            return false;
        }
        return node.isValueNode() && node.asText().contains(value);
    }

    private static boolean hasId(JsonNode node) {
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (("id".equals(field.getKey()) || field.getKey().endsWith("Id"))
                    && field.getValue() != null && !field.getValue().isNull()
                    && !field.getValue().asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean success(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        if (hasFailureText(node, "error") || hasFailureText(node, "errorCode")
                || hasFailureText(node, "errorMessage")) {
            return false;
        }
        JsonNode success = node.get("success");
        if (success != null && success.isBoolean() && !success.asBoolean()) return false;
        JsonNode status = node.get("status");
        boolean job = node.has("jobId");
        if (job) {
            // Job results carry jobId and use job lifecycle states. Only a completed job
            // status is a successful prerequisite; IDs alone must not rescue FAILED/QUEUED/RUNNING.
            return status != null && status.isTextual()
                    && SUCCESS_STATUSES.contains(status.asText().toUpperCase(Locale.ROOT));
        }
        if (status != null && !status.isNull()) {
            if (!status.isTextual()) return false;
            String normalized = status.asText().toUpperCase(Locale.ROOT);
            if (SUCCESS_STATUSES.contains(normalized)) return true;
            // Resource snapshots use domain states such as TODO/SCHEDULED/REJECTED; a returned
            // resource with an ID is the successful write. Unfinished and hard-failed states are not.
            if (PENDING_STATUSES.contains(normalized) || FAILURE_STATUSES.contains(normalized)) return false;
            return hasId(node);
        }
        if (success != null && success.isBoolean()) return true;
        return hasId(node);
    }

    private static boolean hasFailureText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return false;
        return value.isObject() || value.isArray() ? value.size() > 0 : !value.asText().isBlank();
    }

    /**
     * Removes the prompt-injection safety envelope added by external tools. The payload may be JSON
     * (structured tools) or plain text (search/knowledge adapters); the caller decides how to read it.
     */
    private static String unwrapUntrusted(String raw) {
        String value = raw.strip();
        String open = "<untrusted_external_content";
        String close = "</untrusted_external_content>";
        if (!value.startsWith(open)) return raw;
        int openEnd = value.indexOf('>');
        int closeStart = value.lastIndexOf(close);
        if (openEnd < 0 || closeStart <= openEnd) return raw;
        String inner = value.substring(openEnd + 1, closeStart).strip();
        if (inner.startsWith("安全提示：")) {
            int newline = inner.indexOf('\n');
            if (newline >= 0) inner = inner.substring(newline + 1).strip();
        }
        return inner;
    }

    /** Supports $, $.field, $[0] and $.items[0].field for verifiers; anything else is unresolved. */
    private static JsonNode at(JsonNode root, String path) {
        if (path == null || path.isBlank() || "$".equals(path.strip())) return root;
        String value = path.strip();
        if (value.startsWith("$")) value = value.substring(1);
        JsonNode current = root;
        int index = 0;
        while (index < value.length() && current != null) {
            char character = value.charAt(index);
            if (character == '.') {
                int start = ++index;
                while (index < value.length() && value.charAt(index) != '.' && value.charAt(index) != '[') index++;
                if (start == index) return null;
                current = current.get(value.substring(start, index));
            } else if (character == '[') {
                int end = value.indexOf(']', index);
                if (end < 0) return null;
                String number = value.substring(index + 1, end).strip();
                if (!number.matches("\\d+")) return null;
                long parsed;
                try {
                    parsed = Long.parseLong(number);
                } catch (NumberFormatException failure) {
                    return null;
                }
                if (parsed > Integer.MAX_VALUE) return null;
                current = current.get((int) parsed);
                index = end + 1;
            } else {
                return null;
            }
        }
        return current;
    }

    private static String summarize(JsonNode node) {
        if (node == null) return "missing";
        if (node.isArray()) return "array(" + node.size() + ")";
        if (node.isObject()) return "object(" + node.size() + " fields)";
        // Do not copy user content into trace evidence; length and scalar values are enough to debug.
        if (node.isTextual()) return "text(" + node.asText().length() + ")";
        return node.asText();
    }

    private static String sha(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The three situations that legitimately justify a new plan; anything else must not re-plan. */
    public boolean mayRevise(StepOutcome outcome) {
        return outcome != null && outcome.failed();
    }

    public String planSummary(AgentPlanStore.Plan plan) {
        StringBuilder summary = new StringBuilder("计划（共 ").append(plan.steps().size()).append(" 步，已核验 ")
                .append(plan.verifiedSteps()).append("）：");
        for (AgentPlanStore.Step step : plan.steps()) {
            summary.append(' ').append(step.index() + 1).append('.').append(step.intent())
                    .append('[').append(step.status()).append("] 完成条件：").append(step.doneWhen()).append(';');
        }
        return summary.toString();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").strip();
    }
}
