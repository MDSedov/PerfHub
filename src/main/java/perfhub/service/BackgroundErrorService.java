package com.example.chronicler.service;

import com.example.chronicler.dto.BackgroundErrorDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.stereotype.Service;

@Service
public class BackgroundErrorService {

    public static final String GLOBAL_SCOPE = "global";
    public static final String LOAD_TESTS_SCOPE = "load-tests";

    private static final int MAX_QUEUE_SIZE = 100;

    private final Queue<BackgroundErrorDto> errors = new ConcurrentLinkedQueue<>();

    public void publish(String message) {
        publish(GLOBAL_SCOPE, message);
    }

    public void publish(String scope, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        errors.offer(new BackgroundErrorDto(message, Instant.now(), normalizeScope(scope)));
        trimQueue();
    }

    public List<BackgroundErrorDto> drain() {
        return drain(GLOBAL_SCOPE);
    }

    public List<BackgroundErrorDto> drain(String scope) {
        String requestedScope = normalizeScope(scope);
        List<BackgroundErrorDto> result = new ArrayList<>();
        List<BackgroundErrorDto> deferred = new ArrayList<>();
        BackgroundErrorDto item;
        while ((item = errors.poll()) != null) {
            String itemScope = normalizeScope(item.scope());
            if (GLOBAL_SCOPE.equals(itemScope) || requestedScope.equals(itemScope)) {
                result.add(item);
            } else {
                deferred.add(item);
            }
        }
        errors.addAll(deferred);
        return result;
    }

    private void trimQueue() {
        while (errors.size() > MAX_QUEUE_SIZE) {
            errors.poll();
        }
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return GLOBAL_SCOPE;
        }
        return scope.trim().toLowerCase();
    }
}
