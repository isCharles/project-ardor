package com.projectardor.platform.usage;

import java.util.UUID;

/** Request bodies that can safely reuse a quota charge on transport retry. */
public interface QuotaRequestId {
    UUID requestId();
}
