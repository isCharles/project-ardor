package com.projectardor.auth.service;

public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException() {
        super("该邮箱已注册");
    }
}

