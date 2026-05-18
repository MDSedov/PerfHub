package perfhub.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;

import perfhub.dto.jira.JiraReleaseDistribution;
import perfhub.dto.jira.JiraReleaseIssue;
import perfhub.exception.JiraClientException;
import perfhub.service.ConfigurationProvider;
import java.util.List;
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

    @Test
    void findReleaseIssueByReleaseShouldMatchVersionDescriptionAndBuildIssueUrl() {
        JiraClient client = new JiraClient(restTemplate, new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "https://jira.example",
                key("api-path"), "/rest/api/2",
                key("personal-access-token"), "jira-token",
                key("project-name"), "EPROFILE")));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://jira.example/rest/api/2/project/EPROFILE/versions"))
                .andExpect(MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer jira-token"))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        [
                          {"name":"EPROFILE-4150","description":"2.17.0"},
                          {"name":"EPROFILE-4151","description":"2.18.0"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        Optional<JiraReleaseIssue> result = client.findReleaseIssueByRelease("2.18.0");

        mockServer.verify();
        assertThat(result).hasValue(new JiraReleaseIssue("EPROFILE-4151", "https://jira.example/browse/EPROFILE-4151"));
    }

    @Test
    void findReleaseIssueByReleaseShouldFallbackToSearchWhenVersionNameIsNotIssueKey() {
        JiraClient client = new JiraClient(restTemplate, new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "https://jira.example",
                key("api-path"), "/rest/api/2",
                key("personal-access-token"), "jira-token",
                key("project-name"), "EPROFILE")));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://jira.example/rest/api/2/project/EPROFILE/versions"))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        [
                          {"name":"2.17.0","description":"2.17.0"}
                        ]
                        """, MediaType.APPLICATION_JSON));
        mockServer.expect(MockRestRequestMatchers.requestTo(startsWith("https://jira.example/rest/api/2/search?")))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer jira-token"))
                .andExpect(MockRestRequestMatchers.queryParam(
                        "jql",
                        "project%20%3D%20%22EPROFILE%22%20AND%20text%20~%20%222.17.0%22"))
                .andExpect(MockRestRequestMatchers.queryParam("fields", "summary,description"))
                .andExpect(MockRestRequestMatchers.queryParam("maxResults", "50"))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {
                          "issues": [
                            {"key":"EPROFILE-4100","fields":{"summary":"Релиз","description":"2.16.0"}},
                            {"key":"EPROFILE-4151","fields":{"summary":"Релиз","description":"2.17.0"}}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        Optional<JiraReleaseIssue> result = client.findReleaseIssueByRelease("2.17.0");

        mockServer.verify();
        assertThat(result).hasValue(new JiraReleaseIssue("EPROFILE-4151", "https://jira.example/browse/EPROFILE-4151"));
    }

    @Test
    void findReleaseDistributionsByReleaseShouldParseRenderedInstallationTable() {
        JiraClient client = new JiraClient(restTemplate, new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "https://jira.example",
                key("api-path"), "/rest/api/2",
                key("personal-access-token"), "jira-token",
                key("project-name"), "EPROFILE")));

        mockServer.expect(MockRestRequestMatchers.requestTo(startsWith("https://jira.example/rest/api/2/search?")))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer jira-token"))
                .andExpect(MockRestRequestMatchers.queryParam("fields", "summary,description,fixVersions"))
                .andExpect(MockRestRequestMatchers.queryParam("expand", "renderedFields"))
                .andExpect(MockRestRequestMatchers.queryParam("maxResults", "50"))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {
                          "issues": [
                            {
                              "key":"EPROFILE-5000",
                              "renderedFields":{
                                "description":"<h2>Установка на НТ</h2><table><tr><th>Статус</th><th>Сервис</th><th>Дистрибутив</th></tr><tr><td>Готов</td><td><a href=\\"https://dpm.example/service/profile\\">profile</a></td><td><a href=\\"https://nexus.example/repository/sp-02/017/00_5.profile\\">sp-02/017/00_5.profile</a></td></tr></table>"
                              },
                              "fields":{"description":"ignored"}
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<JiraReleaseDistribution> result = client.findReleaseDistributionsByRelease("2.17.0");

        mockServer.verify();
        assertThat(result)
                .containsExactly(new JiraReleaseDistribution(
                        "profile",
                        "https://dpm.example/service/profile",
                        "sp-02/017/00_5.profile",
                        "https://nexus.example/repository/sp-02/017/00_5.profile"));
    }

    @Test
    void findReleaseDistributionsByReleaseShouldFallbackToWikiDescriptionTable() {
        JiraClient client = new JiraClient(restTemplate, new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "https://jira.example",
                key("api-path"), "/rest/api/2",
                key("personal-access-token"), "jira-token",
                key("project-name"), "EPROFILE")));

        mockServer.expect(MockRestRequestMatchers.requestTo(startsWith("https://jira.example/rest/api/2/search?")))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {
                          "issues": [
                            {
                              "key":"EPROFILE-5000",
                              "fields":{
                                "description":"Установка на НТ\\n||Статус||Сервис||Дистрибутив||\\n|Готов|[profile|https://dpm.example/service/profile]|[sp-02/017/00_5.profile|https://nexus.example/repository/sp-02/017/00_5.profile]|"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<JiraReleaseDistribution> result = client.findReleaseDistributionsByRelease("2.17");

        mockServer.verify();
        assertThat(result)
                .containsExactly(new JiraReleaseDistribution(
                        "profile",
                        "https://dpm.example/service/profile",
                        "sp-02/017/00_5.profile",
                        "https://nexus.example/repository/sp-02/017/00_5.profile"));
    }

    @Test
    void isConfiguredForReleaseLookupShouldRequireProjectName() {
        JiraClient client = new JiraClient(restTemplate, new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "https://jira.example",
                key("personal-access-token"), "jira-token")));

        assertThat(client.isConfiguredForReleaseLookup()).isFalse();
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
