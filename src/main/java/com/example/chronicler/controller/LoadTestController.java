package com.example.chronicler.controller;

import com.example.chronicler.dto.tests.LoadTestView;
import com.example.chronicler.repository.LoadTestRepository;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST-контроллер для работы со списком нагрузочных тестов. */
@RestController
@RequestMapping(path = "/api/tests", produces = MediaType.APPLICATION_JSON_VALUE)
public class LoadTestController {

    private final LoadTestRepository loadTestRepository;

    public LoadTestController(LoadTestRepository loadTestRepository) {
        this.loadTestRepository = loadTestRepository;
    }

    @GetMapping
    public List<LoadTestView> listTests() {
        return loadTestRepository.findAllOrderByStartDesc().stream()
                .map(LoadTestView::fromModel)
                .collect(Collectors.toList());
    }
}
