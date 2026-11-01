package com.projectardor.assistant.execution.service;

/**
 * Bounds a tool result before it becomes part of the model's working context.
 *
 * <p>One web page or document read can be larger than the rest of the run combined, and a result that
 * large cannot be recovered later by trimming history. Keeping the head and tail, and naming the call
 * that produced it, lets the model continue from a bounded payload while the full text stays available:
 * the tool span in the run trace holds the complete result, and the model can re-read with a narrower
 * request. The elision note is part of the untrusted payload, not an instruction the tool wrote.</p>
 */
public final class AgentToolResultBudget {
    public static final int DEFAULT_MAX_CHARS = 16_000;
    private static final double HEAD_SHARE = 0.75;

    private AgentToolResultBudget() {}

    /** @param maxChars ceiling for one result; zero or less uses {@link #DEFAULT_MAX_CHARS} */
    public static int resolve(int maxChars) {
        return maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
    }

    /**
     * @param callId the tool call id the model itself will see, so it can tell which result was shortened
     * @return {@code result} unchanged when it already fits, otherwise head + tail plus an elision note
     */
    public static String bound(String result, String tool, String callId, int maxChars) {
        int ceiling = resolve(maxChars);
        if (result == null || result.length() <= ceiling) return result;
        int head = Math.max(1, (int) (ceiling * HEAD_SHARE));
        int tail = Math.max(1, ceiling - head);
        int headEnd = floorCodePoint(result, head);
        int tailStart = ceilCodePoint(result, result.length() - tail);
        String note = "\n\n〔工具输出已截断：" + tool + " 返回 " + result.length() + " 字符，此处保留开头与结尾。"
                + "调用 ID " + (callId == null ? "未知" : callId) + "，完整输出仍在本轮轨迹的工具节点里。"
                + "需要其余内容请再次调用同一工具并缩小范围；不得声称已读取被省略的部分。〕\n\n";
        return result.substring(0, headEnd) + note + result.substring(tailStart);
    }

    /** Never split a surrogate pair: an invalid character would reach the provider. */
    private static int floorCodePoint(String value, int index) {
        return index > 0 && Character.isHighSurrogate(value.charAt(index - 1)) ? index - 1 : index;
    }

    /** A tail that starts on the second half of a pair would begin with a lone surrogate. */
    private static int ceilCodePoint(String value, int index) {
        return index < value.length() && Character.isLowSurrogate(value.charAt(index)) ? index + 1 : index;
    }
}
