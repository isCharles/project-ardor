package com.projectardor.platform.identity.auth.web;

public record CsrfResponse(String token, String headerName) {
}
