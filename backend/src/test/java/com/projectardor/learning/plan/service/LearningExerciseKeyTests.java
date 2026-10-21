package com.projectardor.learning.plan.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;


class LearningExerciseKeyTests {
    @Test void malformedMultipleChoiceKeysCannotReachGrading() {
        var service = mock(LearningPlanService.class, CALLS_REAL_METHODS);
        var mapper = new ObjectMapper();
        for (String key : new String[]{"", ",\"correctOption\":1.5", ",\"correctOption\":-1", ",\"correctOption\":3", ",\"correctOption\":\"0\""}) {
            var exercises = mapper.readTree("""
                    [{"question":"Choice","type":"MULTIPLE_CHOICE","options":["A","B","C"],"rubric":["A"]%s},
                     {"question":"Explain","rubric":["Evidence"]}]
                    """.formatted(key));
            assertThatThrownBy(() -> service.exercises(exercises)).isInstanceOf(IllegalStateException.class);
        }
        var valid = mapper.readTree("""
                [{"question":"Choice","type":"MULTIPLE_CHOICE","options":["A","B","C"],"correctOption":0,"rubric":["A"]},
                 {"question":"Explain","rubric":["Evidence"]}]
                """);
        assertThat(service.exercises(valid).getFirst()).containsEntry("correctOption", 0);
        for (String options : new String[]{"[\"\",\"A\",\"B\",\"C\"]", "[\"A\",\"A\",\"B\"]", "[1,\"B\",\"C\"]"}) {
            var malformed = mapper.readTree("""
                    [{"question":"Choice","type":"MULTIPLE_CHOICE","options":%s,"correctOption":0,"rubric":["A"]},
                     {"question":"Explain","rubric":["Evidence"]}]
                    """.formatted(options));
            assertThatThrownBy(() -> service.exercises(malformed)).isInstanceOf(IllegalStateException.class);
        }
    }
}
