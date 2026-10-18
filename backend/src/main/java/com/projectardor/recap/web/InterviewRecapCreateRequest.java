package com.projectardor.recap.web;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
public record InterviewRecapCreateRequest(
        @NotBlank(message = "面试内容不能为空") @Size(max = 50000, message = "面试内容不能超过 50000 个字符") String content,
        @NotNull(message = "请选择所属面试") UUID jobOpportunityId) {}
