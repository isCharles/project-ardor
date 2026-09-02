package com.projectardor.auth.web;

public record CsrfResponse(String token, String headerName) {
}
