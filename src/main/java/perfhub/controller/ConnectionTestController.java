package perfhub.controller;

import perfhub.dto.configuration.ConnectionTestResponse;
import perfhub.service.ConnectionTestService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/perfhub/connections", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConnectionTestController {

    private final ConnectionTestService connectionTestService;

    public ConnectionTestController(ConnectionTestService connectionTestService) {
        this.connectionTestService = connectionTestService;
    }

    @PostMapping("/{service}/test")
    public ConnectionTestResponse testConnection(@PathVariable("service") String service) {
        return connectionTestService.testConnection(service);
    }
}
