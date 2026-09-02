package com.projectardor.integrations.web;

import com.projectardor.integrations.domain.AuxiliaryApiConfig;
import com.projectardor.integrations.domain.AuxiliaryServiceType;

public record AuxiliaryApiConfigResponse(
        AuxiliaryServiceType serviceType,
        boolean configured,
        String provider,
        String baseUrl,
        String model,
        String keyHint) {

    public static AuxiliaryApiConfigResponse configured(AuxiliaryApiConfig config) {
        return new AuxiliaryApiConfigResponse(
                config.getServiceType(), true, config.getProvider(), config.getBaseUrl(), config.getModel(), config.getKeyHint());
    }

    public static AuxiliaryApiConfigResponse unconfigured(AuxiliaryServiceType type) {
        return new AuxiliaryApiConfigResponse(type, false, "", "", "", null);
    }
}
