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

    private final Queue<BackgroundErrorDto> errors = new ConcurrentLinkedQueue<>();

    public void publish(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        errors.offer(new BackgroundErrorDto(message, Instant.now()));
    }

    public List<BackgroundErrorDto> drain() {
        List<BackgroundErrorDto> result = new ArrayList<>();
        BackgroundErrorDto item;
        while ((item = errors.poll()) != null) {
            result.add(item);
        }
        return result;
    }
}
