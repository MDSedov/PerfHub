package perfhub.controller.admin;

import perfhub.dto.admin.TestTypeDto;
import perfhub.dto.admin.TestTypeSaveRequest;
import perfhub.model.TestType;
import perfhub.service.TestTypeAdminService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/admin/test-types", produces = MediaType.APPLICATION_JSON_VALUE)
public class TestTypeAdminController {

    private final TestTypeAdminService testTypeAdminService;

    public TestTypeAdminController(TestTypeAdminService testTypeAdminService) {
        this.testTypeAdminService = testTypeAdminService;
    }

    @GetMapping
    public List<TestTypeDto> listTypes() {
        return testTypeAdminService.listTypes().stream()
                .map(this::toDto)
                .toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public TestTypeDto createType(@RequestBody TestTypeSaveRequest request) {
        return toDto(testTypeAdminService.createType(toCommand(request)));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TestTypeDto updateType(
            @PathVariable("id") int id,
            @RequestBody TestTypeSaveRequest request) {
        return toDto(testTypeAdminService.updateType(id, toCommand(request)));
    }

    @DeleteMapping("/{id}")
    public void deleteType(@PathVariable("id") int id) {
        testTypeAdminService.deleteType(id);
    }

    private TestTypeAdminService.TestTypeCommand toCommand(TestTypeSaveRequest request) {
        return new TestTypeAdminService.TestTypeCommand(
                request != null ? request.name() : null,
                request != null ? request.code() : null);
    }

    private TestTypeDto toDto(TestType testType) {
        return new TestTypeDto(
                testType.id(),
                testType.name(),
                testType.code(),
                testType.standard(),
                testType.standard() ? "Стандартный" : "Пользовательский");
    }
}
