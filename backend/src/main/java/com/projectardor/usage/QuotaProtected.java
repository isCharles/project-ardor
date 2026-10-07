package com.projectardor.usage;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface QuotaProtected {
    UsageFeature value();
    boolean idempotentRequest() default false;
    /** Allow older clients without request IDs to keep their non-idempotent behavior. */
    boolean optionalRequestId() default false;
}
