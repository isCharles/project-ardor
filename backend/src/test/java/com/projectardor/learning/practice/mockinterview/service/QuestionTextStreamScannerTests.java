package com.projectardor.learning.practice.mockinterview.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionTextStreamScannerTests {

    private static final String DOCUMENT = """
            {"questions":[
              {"questionText":"请介绍一下 Redis 的过期策略。","questionType":"TECHNICAL","evaluationCriteria":["说出惰性删除","说出定期删除"]},
              {"questionText":"项目里你怎么\\n解决超卖？包括 U.S. 版本 v2.0 的取舍。","questionType":"PROJECT","evaluationCriteria":[]}
            ]}""";

    @Test void capturesEveryQuestionTextAndCountsCompletions() {
        List<QuestionTextStreamScanner.Fragment> fragments = new QuestionTextStreamScanner().feed(DOCUMENT);
        assertThat(reconstruct(fragments)).isEqualTo(
                "请介绍一下 Redis 的过期策略。项目里你怎么\n解决超卖？包括 U.S. 版本 v2.0 的取舍。");
        assertThat(fragments.stream().filter(QuestionTextStreamScanner.Fragment::completed).toList())
                .extracting(QuestionTextStreamScanner.Fragment::questionIndex).containsExactly(0, 1);
    }

    @Test void chunkingNeverChangesWhatIsCaptured() {
        for (int size : new int[]{1, 2, 3, 5, 8, 13, 40, DOCUMENT.length()}) {
            QuestionTextStreamScanner scanner = new QuestionTextStreamScanner();
            List<QuestionTextStreamScanner.Fragment> fragments = new ArrayList<>();
            for (int start = 0; start < DOCUMENT.length(); start += size) {
                fragments.addAll(scanner.feed(DOCUMENT.substring(start, Math.min(DOCUMENT.length(), start + size))));
            }
            assertThat(reconstruct(fragments))
                    .as("chunk size %s", size)
                    .isEqualTo("请介绍一下 Redis 的过期策略。项目里你怎么\n解决超卖？包括 U.S. 版本 v2.0 的取舍。");
            assertThat(scanner.completedQuestions()).as("chunk size %s", size).isEqualTo(2);
        }
    }

    @Test void theFirstQuestionCompletesBeforeTheDocumentEnds() {
        QuestionTextStreamScanner scanner = new QuestionTextStreamScanner();
        String firstOnly = DOCUMENT.substring(0, DOCUMENT.indexOf("questionType"));
        assertThat(scanner.feed(firstOnly)).isNotEmpty();
        assertThat(scanner.completedQuestions()).isEqualTo(1);
        assertThat(scanner.documentClosed()).isFalse();
    }

    @Test void siblingFieldsAndNestedArraysAreNeverSpoken() {
        QuestionTextStreamScanner scanner = new QuestionTextStreamScanner();
        List<QuestionTextStreamScanner.Fragment> fragments = scanner.feed(
                "{\"questions\":[{\"questionType\":\"TECHNICAL\",\"evaluationCriteria\":[\"不要朗读我\"],"
                        + "\"questionText\":\"只朗读这句。\"}]}");
        assertThat(reconstruct(fragments)).isEqualTo("只朗读这句。");
        assertThat(fragments).hasSizeLessThanOrEqualTo(2);
    }

    @Test void nestedObjectsMayReuseTheFieldNameWithoutBeingCaptured() {
        QuestionTextStreamScanner scanner = new QuestionTextStreamScanner();
        List<QuestionTextStreamScanner.Fragment> fragments = scanner.feed(
                "{\"questions\":[{\"questionText\":\"外层问题。\",\"meta\":{\"questionText\":\"内层不该读。\"}}]}");
        assertThat(reconstruct(fragments)).isEqualTo("外层问题。");
        assertThat(scanner.completedQuestions()).isEqualTo(1);
    }

    @Test void questionTextOutsideTheQuestionsArrayIsIgnored() {
        QuestionTextStreamScanner scanner = new QuestionTextStreamScanner();
        assertThat(scanner.feed("{\"questionText\":\"不属于题目列表。\"}")).isEmpty();
        assertThat(scanner.completedQuestions()).isZero();
    }

    @Test void aStreamThatStopsMidQuestionIsNotCountedAsComplete() {
        QuestionTextStreamScanner scanner = new QuestionTextStreamScanner();
        List<QuestionTextStreamScanner.Fragment> fragments = scanner.feed(
                "{\"questions\":[{\"questionText\":\"第一题完整。\"},{\"questionText\":\"第二题写到一半");
        assertThat(reconstruct(fragments)).isEqualTo("第一题完整。第二题写到一半");
        assertThat(scanner.completedQuestions()).isEqualTo(1);
        assertThat(fragments.stream().filter(QuestionTextStreamScanner.Fragment::completed).toList())
                .extracting(QuestionTextStreamScanner.Fragment::questionIndex).containsExactly(0);
    }

    @Test void escapesAndUnicodeDecodeIntoSpeakableText() {
        QuestionTextStreamScanner scanner = new QuestionTextStreamScanner();
        List<QuestionTextStreamScanner.Fragment> fragments = scanner.feed(
                "{\"questions\":[{\"questionText\":\"他说\\\"你好\\\"，然后 \\u964d 低 QPS。\"}]}");
        assertThat(reconstruct(fragments)).isEqualTo("他说\"你好\"，然后 降 低 QPS。");
        assertThat(scanner.completedQuestions()).isEqualTo(1);
    }

    @Test void textSurfacesBeforeTheQuestionIsClosed() {
        QuestionTextStreamScanner scanner = new QuestionTextStreamScanner();
        assertThat(scanner.feed("{\"questions\":[{\"questionText\":\"第一题还在生成"))
                .containsExactly(new QuestionTextStreamScanner.Fragment(0, "第一题还在生成", false));
        assertThat(scanner.completedQuestions()).isZero();
    }

    private static String reconstruct(List<QuestionTextStreamScanner.Fragment> fragments) {
        StringBuilder text = new StringBuilder();
        for (QuestionTextStreamScanner.Fragment fragment : fragments) text.append(fragment.text());
        return text.toString();
    }
}
