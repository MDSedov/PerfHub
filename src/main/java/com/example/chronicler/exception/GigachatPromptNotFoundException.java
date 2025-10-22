package com.example.chronicler.exception;

public class GigachatPromptNotFoundException extends RuntimeException {

    public GigachatPromptNotFoundException(String promptKey) {
        super("Prompt not found: " + promptKey);
    }
}
