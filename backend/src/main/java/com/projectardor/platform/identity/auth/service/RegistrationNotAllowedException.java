package com.projectardor.platform.identity.auth.service;

public class RegistrationNotAllowedException extends RuntimeException {
    public RegistrationNotAllowedException() {
        super("当前为邀请制试用，该邮箱未获准入");
    }
}
