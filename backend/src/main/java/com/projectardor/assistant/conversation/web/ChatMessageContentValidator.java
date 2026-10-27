package com.projectardor.assistant.conversation.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ChatMessageContentValidator implements ConstraintValidator<ChatMessageContent, AgentMessageRequest> {
    @Override
    public boolean isValid(AgentMessageRequest request, ConstraintValidatorContext context) {
        if (request == null) return true;
        if (request.message() != null && !request.message().isBlank() || !request.imageIds().isEmpty()) return true;
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("消息不能为空")
                .addPropertyNode("message").addConstraintViolation();
        return false;
    }
}
