package com.projectardor.jobsearch.applicationactivity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApplicationRhythmIntentTests {
    @Test void approximateResumeCountsAreDetectedInChineseAndEnglish() {
        for (String request : new String[]{
                "今天打招呼 89 个、发了约 5 份简历",
                "今天打招呼 89 个，发了大约五份简历",
                "打招呼 89 个，简历发了 5 份左右",
                "I sent 89 greetings and about 5 resumes today",
                "around 5 resumes sent today"}) {
            assertThat(ApplicationRhythmIntent.approximateResumeCount(request)).as(request).isTrue();
        }
    }

    @Test void exactResumeCountsAndGreetingOnlyMessagesAreNotTreatedAsApproximate() {
        for (String request : new String[]{
                "今天打招呼 89 个，发了 5 份简历",
                "今天打了 89 个招呼",
                "确认一下，简历就是 5 份",
                "I sent exactly 5 resumes today",
                "约一下面试时间"}) {
            assertThat(ApplicationRhythmIntent.approximateResumeCount(request)).as(request).isFalse();
        }
    }
}
