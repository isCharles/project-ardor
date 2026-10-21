package com.projectardor.assistant.automation.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AutomationCreateRequest(
        @NotBlank(message = "自动化名称不能为空") @Size(max = 160, message = "自动化名称不能超过 160 字") String title,
        @NotBlank(message = "自动化要发送的消息不能为空") @Size(max = 20000, message = "自动化消息不能超过 20000 字") String prompt,
        @NotBlank(message = "执行时间不能为空") @Size(max = 120, message = "执行时间不能超过 120 字") String cronExpression,
        @Size(max = 64, message = "时区不能超过 64 字") String timezone) {}
