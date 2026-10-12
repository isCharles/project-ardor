package com.projectardor.aijobs;
import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import com.projectardor.usage.QuotaRequestId;
public record AiJobRequest(@NotNull UUID requestId) implements QuotaRequestId {}
