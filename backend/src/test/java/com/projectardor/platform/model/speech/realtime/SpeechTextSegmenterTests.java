package com.projectardor.platform.model.speech.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpeechTextSegmenterTests {

    private long now;
    private final java.util.function.LongSupplier clock = () -> now;

    private SpeechTextSegmenter segmenter(int max, int min) {
        return new SpeechTextSegmenter(max, min, clock);
    }

    @Test void splitsOnChineseAndLatinSentencePunctuation() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        assertThat(segmenter.accept("先讲思路。再讲取舍！最后说结果？")).containsExactly(
                "先讲思路。", "再讲取舍！", "最后说结果？");
        assertThat(segmenter.pendingCharacters()).isZero();
    }

    @Test void keepsDecimalsAndVersionNumbersInsideOneSegment() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        assertThat(segmenter.accept("首音频是 3.5 秒，版本 v2.0 上线。好的。")).containsExactly(
                "首音频是 3.5 秒，版本 v2.0 上线。");
    }

    @Test void doesNotBreakInTheMiddleOfAnEllipsisRun() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        assertThat(segmenter.accept("这个点我再想想……不过结论是这样。")).containsExactly(
                "这个点我再想想……不过结论是这样。");
    }

    @Test void holdsVeryShortSentencesTogether() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        assertThat(segmenter.accept("好的。明白了。")).containsExactly("好的。明白了。");
    }

    @Test void capsRunonTextAtAClauseBoundary() {
        SpeechTextSegmenter segmenter = segmenter(24, 4);
        List<String> segments = segmenter.accept(
                "这道题要从读写分离讲起，先说为什么要分离，再说中间件怎么选，最后说一致性怎么兜住。");
        assertThat(segments).isNotEmpty();
        assertThat(segments.get(0)).hasSizeLessThanOrEqualTo(24);
        assertThat(String.join("", segments)).isEqualTo(
                "这道题要从读写分离讲起，先说为什么要分离，再说中间件怎么选，最后说一致性怎么兜住。");
    }

    @Test void flushWaitingSpeaksOnlyOnceTheThresholdPasses() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        now = 1_000;
        assertThat(segmenter.accept("这句话说了一半没有标点")).isEmpty();
        assertThat(segmenter.flushWaiting(600)).isEmpty();
        now = 1_700;
        assertThat(segmenter.flushWaiting(600)).containsExactly("这句话说了一半没有标点");
    }

    @Test void finishSpeaksTheUnpunctuatedTail() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        assertThat(segmenter.accept("第一题完成了。第二题没有标点")).containsExactly("第一题完成了。");
        assertThat(segmenter.finish()).containsExactly("第二题没有标点");
    }

    @Test void abandonLeavesAPartialSentenceUnspoken() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        assertThat(segmenter.accept("这句话还没说完就失败了")).isEmpty();
        segmenter.abandon();
        assertThat(segmenter.finish()).isEmpty();
        assertThat(segmenter.statistics().emittedSegments()).isZero();
    }

    @Test void readsLinkLabelsAndDropsMarkupWithoutDroppingWords() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        List<String> segments = segmenter.accept("## 幂等设计\n- 用 **Redis** 做分布式锁，文档见[内部规范](https://example.internal/doc)。\n");
        assertThat(segments).containsExactly("幂等设计", "用 Redis 做分布式锁，文档见内部规范。");
        assertThat(segmenter.statistics().droppedLinks()).isEqualTo(1);
    }

    @Test void skipsFencedCodeEvenWhenTheFenceSplitsAcrossDeltas() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        assertThat(segmenter.accept("思路如下：\n```java\n")).containsExactly("思路如下：");
        assertThat(segmenter.accept("int x = 1;\n```")).isEmpty();
        assertThat(segmenter.accept("\n这样就讲完了。\n")).containsExactly("这样就讲完了。");
        assertThat(segmenter.statistics().droppedCodeCharacters()).isPositive();
    }

    @Test void anUnclosedFenceStillSpeaksTheProseBeforeIt() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        assertThat(segmenter.accept("先讲结论。然后贴代码：\n```java\nint x = 1;"))
                .containsExactly("先讲结论。", "然后贴代码：");
        assertThat(segmenter.finish()).isEmpty();
        assertThat(segmenter.statistics().droppedCodeCharacters()).isPositive();
    }

    @Test void punctuationSplitAcrossDeltasDoesNotDoubleSpeak() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        List<String> segments = new ArrayList<>();
        for (String delta : List.of("为什么选", " RocketMQ？", "因为要", "顺序消费。")) {
            segments.addAll(segmenter.accept(delta));
        }
        segments.addAll(segmenter.finish());
        assertThat(segments).containsExactly("为什么选 RocketMQ？", "因为要顺序消费。");
    }

    @Test void statisticsCountWhatWasActuallySpoken() {
        SpeechTextSegmenter segmenter = segmenter(120, 4);
        segmenter.accept("第一句。第二句。");
        assertThat(segmenter.statistics().emittedSegments()).isEqualTo(2);
        assertThat(segmenter.statistics().emittedCharacters()).isEqualTo(8);
    }
}
