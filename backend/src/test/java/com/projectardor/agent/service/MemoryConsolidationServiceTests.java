package com.projectardor.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.projectardor.common.json.LlmJsonParser;

import tools.jackson.databind.ObjectMapper;

class MemoryConsolidationServiceTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmJsonParser PARSER = new LlmJsonParser(MAPPER);

    @Test
    void keepsOnlyCandidatesEvidencedByUserMessages() {
        String output = """
                {"memories":[
                  {"op":"ADD","content":"用户目前研二，三年制","kind":"profile","confidence":0.85,"evidence":"现在研二了"},
                  {"op":"ADD","content":"用户喜欢远程工作","kind":"preference","confidence":0.9,"evidence":"我希望远程工作"}
                ]}""";
        var candidates = MemoryConsolidationService.candidates(PARSER.parseObject(output),
                List.of("现在研二了，三年制"), Set.of());
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).content()).contains("研二");
        assertThat(candidates.get(0).confidence()).isEqualTo(0.85);
    }

    @Test
    void updateMustTargetAnExistingOwnedMemory() {
        UUID existing = UUID.randomUUID();
        String output = """
                {"memories":[
                  {"op":"UPDATE","id":"%s","content":"用户目前研二","kind":"profile","confidence":0.8,"evidence":"现在研二了"},
                  {"op":"UPDATE","id":"%s","content":"用户目前博一","kind":"profile","confidence":0.8,"evidence":"现在研二了"}
                ]}""".formatted(existing, UUID.randomUUID());
        var candidates = MemoryConsolidationService.candidates(PARSER.parseObject(output),
                List.of("现在研二了"), Set.of(existing));
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).id()).isEqualTo(existing);
        assertThat(candidates.get(0).op()).isEqualTo("UPDATE");
    }

    @Test
    void capsCandidatesAndClampsConfidence() {
        StringBuilder memories = new StringBuilder("{\"memories\":[");
        for (int index = 0; index < 6; index++) {
            if (index > 0) memories.append(',');
            memories.append("{\"op\":\"ADD\",\"content\":\"事实").append(index)
                    .append("\",\"kind\":\"weird\",\"confidence\":9,\"evidence\":\"现在研二了\"}");
        }
        memories.append("]}");
        var candidates = MemoryConsolidationService.candidates(PARSER.parseObject(memories.toString()),
                List.of("现在研二了"), Set.of());
        assertThat(candidates).hasSize(3);
        assertThat(candidates).allSatisfy(candidate -> {
            assertThat(candidate.kind()).isEqualTo("fact");
            assertThat(candidate.confidence()).isEqualTo(0.9);
        });
    }

    @Test
    void emptyOrMalformedOutputProducesNoWrites() {
        assertThat(MemoryConsolidationService.candidates(PARSER.parseObject("{\"memories\":[]}"),
                List.of("现在研二了"), Set.of())).isEmpty();
        assertThat(MemoryConsolidationService.candidates(null, List.of(), Set.of())).isEmpty();
    }
}
