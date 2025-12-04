package com.example.chronicler.controller;

import com.example.chronicler.dto.BackgroundErrorDto;
import com.example.chronicler.service.BackgroundErrorService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/background/errors", produces = MediaType.APPLICATION_JSON_VALUE)
public class BackgroundErrorController {

    private final BackgroundErrorService backgroundErrorService;

    public BackgroundErrorController(BackgroundErrorService backgroundErrorService) {
        this.backgroundErrorService = backgroundErrorService;
    }

    @GetMapping
    public List<BackgroundErrorDto> consumeErrors() {
        return backgroundErrorService.drain();
    }
}
