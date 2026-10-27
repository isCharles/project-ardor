package com.projectardor.learning.plan.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Detects mechanical answer cues, not semantic correctness. Only newly generated exercises pass here. */
final class LearningExerciseQuality {
    static final String VERSION = "learning-objective-v1";
    static final String GUIDANCE = """
            客观题出题规范：
            - 单选题只设一个最佳答案。题干明确前提、版本和适用边界，避免缺少条件而多个选项都成立。
            - 干扰项来自常见误解或相邻概念；每项在相同场景下可比较，不用明显无关的概念凑数。
            - 选项的句式、信息量和长度相近；不要只在正确项加入解释、限定条件或专业术语。
              不得靠补无意义文字凑长度，也不得截短正确答案。不要固定正确答案的位置。
            - 不用“以上都正确”“以上都错误”等组合选项，避免相互包含的选项。
            - 判断题一次只判断一个明确命题；需要条件时写入题干。避免双重否定、故意绕口，
              不靠“总是/一定/绝不”等绝对措辞提示错误；有多道判断题时兼顾正确与错误命题。
            - rubric 写出正确答案成立的依据，以及主要干扰项错在哪里，供服务端批改使用；不要在题干泄露答案。
            输出前自检唯一答案、适用条件、干扰项合理性和长度线索；不合格先改题再输出。
            """;

    static List<String> issues(JsonNode exercises) {
        List<String> issues = new ArrayList<>();
        if (!exercises.isArray()) return issues;
        for (int index = 0; index < exercises.size(); index++) {
            JsonNode item = exercises.get(index);
            if (!"MULTIPLE_CHOICE".equals(item.path("type").asText())) continue;
            JsonNode options = item.path("options"), answer = item.path("correctOption");
            if (!options.isArray() || options.size() < 3 || options.size() > 5 || !answer.isInt()
                    || answer.asInt() < 0 || answer.asInt() >= options.size()) continue;
            var normalized = new java.util.HashSet<String>();
            var lengths = new ArrayList<Integer>();
            boolean textual = true;
            for (JsonNode option : options) {
                if (!option.isTextual()) { textual = false; break; }
                String text = Normalizer.normalize(option.asText(), Normalizer.Form.NFC).strip();
                // Case and inner spaces can change program semantics; never fold them away.
                if (!normalized.add(text)) issues.add("第 " + (index + 1) + " 题存在 Unicode 等价或首尾空白不同的重复选项");
                lengths.add(text.codePointCount(0, text.length()));
            }
            if (!textual) continue;
            int correctLength = lengths.get(answer.asInt());
            var distractors = new ArrayList<>(lengths); distractors.remove(answer.asInt()); distractors.sort(Integer::compareTo);
            int median = distractors.get(distractors.size() / 2);
            if (correctLength > 2L * Math.max(1, median) && correctLength - median >= 24)
                issues.add("第 " + (index + 1) + " 题正确项明显长于干扰项；重新设计信息量相近的选项，勿截短答案或灌水");
        }
        return List.copyOf(issues);
    }
}
