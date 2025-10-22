package com.example.chronicler.controller;

import com.example.chronicler.dto.gigachat.GigachatRequest;
import com.example.chronicler.dto.gigachat.GigachatTokenResponse;
import com.example.chronicler.service.GigachatService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/gigachat", produces = MediaType.APPLICATION_JSON_VALUE)
public class GigachatController {

    private final GigachatService gigachatService;

    public GigachatController(GigachatService gigachatService) {
        this.gigachatService = gigachatService;
    }

    @GetMapping("/token")
    public ResponseEntity<GigachatTokenResponse> fetchToken() {
        GigachatTokenResponse response = gigachatService.fetchAccessToken();
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/request/", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> requestCompletion(@Valid @RequestBody GigachatRequest request) {
        JsonNode response = gigachatService.requestAnalysis(request);
        return ResponseEntity.ok(response);
    }
}
