package com.projectardor.materials.resume.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class ResumeReportV3Tests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void keepsConcreteBenchmarkAndDropsExtractionArtifacts() {
        var issues = mapper.readTree("""
                [
                  {"severity":"B","dimensionId":"technicalDepth","category":"技术术语",
                   "problem":"把 Redis 缓存描述成消息队列","why":"术语错误会让面试官怀疑技术真实性",
                   "action":"改成 Redis 作为缓存，RocketMQ 作为削峰队列",
                   "benchmark":"顶级候选人会写清缓存一致性、击穿处理和队列积压的取舍",
                   "evidence":"Redis 做消息队列削峰"},
                  {"severity":"C","dimensionId":"clarity","category":"格式",
                   "problem":"PDF 断行导致英文术语被拆开","why":"影响扫描","action":"检查断行",
                   "benchmark":"排版清晰","evidence":"Redis"}
                ]
                """);
        var result = ResumeReportV3.issues(issues, "Redis 做消息队列削峰");
        assertEquals(1, result.size());
        assertEquals("顶级候选人会写清缓存一致性、击穿处理和队列积压的取舍", result.get(0).benchmark());
    }
}
