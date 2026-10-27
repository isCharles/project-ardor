package com.projectardor.assistant.conversation.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Message text may be empty only when the request carries at least one image. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ChatMessageContentValidator.class)
public @interface ChatMessageContent {
    String message() default "消息或图片不能为空";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
