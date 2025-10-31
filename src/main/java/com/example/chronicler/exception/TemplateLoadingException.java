package com.example.chronicler.exception;

public class TemplateLoadingException extends RuntimeException {

    public TemplateLoadingException(String message) {
        super(message);
    }

    public TemplateLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}
