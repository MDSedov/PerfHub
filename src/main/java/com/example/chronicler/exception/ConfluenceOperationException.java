package com.example.chronicler.exception;

public class ConfluenceOperationException extends RuntimeException {

    public ConfluenceOperationException(String message) {
        super(message);
    }

    public ConfluenceOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
