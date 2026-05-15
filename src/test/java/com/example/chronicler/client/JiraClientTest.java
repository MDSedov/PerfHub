package com.example.chronicler.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chronicler.exception.JiraClientException;
import com.example.chronicler.service.ConfigurationProvider;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;

class JiraClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void checkConnectionShouldSendBearerTokenToDefaultApiPath() {
        JiraClient client = new JiraClient(restTemplate, new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "https://jira.example",
                key("personal-access-token"), "jira-token")));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://jira.example/rest/api/2/myself"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer jira-token"))
                .andRespond(MockRestResponseCreators.withSuccess("{}", MediaType.APPLICATION_JSON));

        String status = client.checkConnection();

        mockServer.verify();
        assertThat(status).isEqualTo("200 OK");
    }

    @Test
    void checkConnectionShouldUseConfiguredApiPath() {
        JiraClient client = new JiraClient(restTemplate, new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "https://jira.example/",
                key("api-path"), "/rest/api/3/",
                key("personal-access-token"), "jira-token")));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://jira.example/rest/api/3/myself"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess("{}", MediaType.APPLICATION_JSON));

        String status = client.checkConnection();

        mockServer.verify();
        assertThat(status).isEqualTo("200 OK");
    }

    @Test
    void checkConnectionShouldExposeHttpFailureDetails() {
        JiraClient client = new JiraClient(restTemplate, new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "https://jira.example",
                key("personal-access-token"), "bad-token")));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://jira.example/rest/api/2/myself"))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"errorMessages\":[\"Token is invalid\"]}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(client::checkConnection)
                .isInstanceOf(JiraClientException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("Token is invalid");
    }

    private static String key(String key) {
        return "jira:" + key;
    }

    private static final class InMemoryConfigurationProvider implements ConfigurationProvider {

        private final Map<String, String> values;

        private InMemoryConfigurationProvider(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public String getRequiredValue(String service, String key) {
            return findValue(service, key)
                    .orElseThrow(() -> new IllegalStateException("Missing config for " + service + ":" + key));
        }

        @Override
        public Optional<String> findValue(String service, String key) {
            return Optional.ofNullable(values.get(service + ":" + key));
        }
    }
}
