package perfhub.controller;

import perfhub.dto.BackgroundErrorDto;
import perfhub.service.BackgroundErrorService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/background/errors", produces = MediaType.APPLICATION_JSON_VALUE)
public class BackgroundErrorController {

    private final BackgroundErrorService backgroundErrorService;

    public BackgroundErrorController(BackgroundErrorService backgroundErrorService) {
        this.backgroundErrorService = backgroundErrorService;
    }

    @GetMapping
    public List<BackgroundErrorDto> consumeErrors(@RequestParam(value = "scope", required = false) String scope) {
        return backgroundErrorService.drain(scope);
    }
}
