package com.projectardor.platform.usage;

import java.time.Instant;

public class QuotaExceededException extends RuntimeException {
    private final UsageFeature feature;
    private final QuotaDecision reason;
    private final Instant resetAt;

    public QuotaExceededException(UsageFeature feature, QuotaDecision reason, Instant resetAt) {
        super(switch (reason) {
            case MONTHLY_LIMIT -> "本月该功能额度已用完";
            case WEEKLY_LIMIT -> "本周该功能额度已用完";
            case TOKEN_LIMIT -> "本月 Token 额度已用完，可在设置中使用资源包兑换码";
            case GUARD_UNAVAILABLE -> "额度服务暂时无法确认，请稍后重试";
            default -> "请求过于频繁，请稍后再试";
        });
        this.feature = feature;
        this.reason = reason;
        this.resetAt = resetAt;
    }

    public UsageFeature feature() { return feature; }
    public QuotaDecision reason() { return reason; }
    public Instant resetAt() { return resetAt; }
}
