package com.example.chronicler.vm;

class VmQueryNotFoundException extends RuntimeException {

    VmQueryNotFoundException(String type) {
        super("No VictoriaMetrics query configured for type: " + type);
    }
}
