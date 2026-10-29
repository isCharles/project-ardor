package com.projectardor.platform.usage;

import java.time.Instant;

public class QuotaExceededException extends RuntimeException {
    private final UsageFeature feature;
    private final QuotaStore.Decision reason;
    private final Instant resetAt;

    public QuotaExceededException(UsageFeature feature, QuotaStore.Decision reason, Instant resetAt) {
        super(reason == QuotaStore.Decision.MONTHLY_LIMIT
                ? "本月该功能额度已用完"
                : reason == QuotaStore.Decision.WEEKLY_LIMIT
                        ? "本周该功能额度已用完"
                        : reason == QuotaStore.Decision.TOKEN_LIMIT
                                ? "本月 Token 额度已用完，可在设置中使用资源包兑换码" : "请求过于频繁，请稍后再试");
        this.feature = feature;
        this.reason = reason;
        this.resetAt = resetAt;
    }

    public UsageFeature feature() { return feature; }
    public QuotaStore.Decision reason() { return reason; }
    public Instant resetAt() { return resetAt; }
}
