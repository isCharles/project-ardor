package com.projectardor.assistant.execution.service;

import java.util.Locale;
import java.util.regex.Pattern;

import com.projectardor.platform.model.llm.service.LlmConfigService;

public final class AgentImageModelPolicy {
    private static final Pattern OPENAI_IMAGES = Pattern.compile(".*(?:^|[/:.\\-_])(gpt-(?:4o|4\\.1|4\\.5|5)|chatgpt-4o|o[134](?:[/:.\\-_]|$)).*");
    private static final Pattern CLAUDE_IMAGES = Pattern.compile(".*(?:claude-(?:3|4|5)|(?:opus|sonnet|haiku)[/:.\\-_](?:4|5)).*");
    private static final Pattern GEMINI_IMAGES = Pattern.compile(".*gemini[/:.\\-_](?:1\\.5|2|3).*");
    // Official DeepSeek now routes the legacy v4-flash names to V4.1 Flash, which accepts images;
    // v4-pro remains text-only.
    private static final Pattern DEEPSEEK_IMAGES = Pattern.compile(
            ".*(?:^|[/:.\\-_])deepseek-(?:v4(?:\\.1)?-)?flash(?:-vision-exp)?(?:$|[/:.\\-_].*)?");

    private AgentImageModelPolicy() {}

    public static boolean supports(LlmConfigService.LlmRuntimeConfig config) {
        String model = config.model() == null ? "" : config.model().toLowerCase(Locale.ROOT).strip();
        return OPENAI_IMAGES.matcher(model).matches() || CLAUDE_IMAGES.matcher(model).matches()
                || GEMINI_IMAGES.matcher(model).matches() || DEEPSEEK_IMAGES.matcher(model).matches()
                || model.matches(".*qwen.*vl.*");
    }

    public static void requireSupport(LlmConfigService.LlmRuntimeConfig config) {
        if (!supports(config)) {
            throw new IllegalArgumentException("当前模型未声明支持图片，请切换支持图片的模型");
        }
    }
}
