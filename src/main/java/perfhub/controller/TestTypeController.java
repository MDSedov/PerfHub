package perfhub.controller;

import perfhub.dto.tests.TestTypeOptionDto;
import perfhub.service.TestTypeAdminService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/test-types", produces = MediaType.APPLICATION_JSON_VALUE)
public class TestTypeController {

    private final TestTypeAdminService testTypeAdminService;

    public TestTypeController(TestTypeAdminService testTypeAdminService) {
        this.testTypeAdminService = testTypeAdminService;
    }

    @GetMapping
    public List<TestTypeOptionDto> listTypes() {
        return testTypeAdminService.listTypes().stream()
                .map(type -> new TestTypeOptionDto(type.id(), type.code(), type.name(), type.standard()))
                .toList();
    }
}
