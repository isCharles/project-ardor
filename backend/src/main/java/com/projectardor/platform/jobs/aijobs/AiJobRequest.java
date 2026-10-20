package com.projectardor.platform.jobs.aijobs;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import com.projectardor.platform.usage.QuotaRequestId;
public record AiJobRequest(@NotNull UUID requestId) implements QuotaRequestId {}
