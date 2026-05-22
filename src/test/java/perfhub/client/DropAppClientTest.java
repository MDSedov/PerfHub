package perfhub.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestTemplate;
import perfhub.model.DropAppConfigurationComponent;
import perfhub.service.ConfigurationProvider;

class DropAppClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private DropAppClient dropAppClient;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        ConfigurationProvider configurationProvider = new InMemoryConfigurationProvider(Map.of(
                key("base-url"), "https://kubernetes.example",
                key("token"), "test-token"));
        dropAppClient = new DropAppClient(restTemplate, configurationProvider, new ObjectMapper());
    }

    @Test
    void fetchConfigurationUsesVersionLabelAsReleaseAndPreservesHotfixSuffix() {
        mockServer.expect(MockRestRequestMatchers.requestTo(
                        "https://kubernetes.example/apis/apps/v1/namespaces/lt/deployments"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {
                          "items": [
                            {
                              "metadata": {
                                "name": "billing",
                                "labels": {
                                  "helpers.app.kubernetes.io/config-branch": "release/9.99.0",
                                  "helpers.app.kubernetes.io/version": "2.17.0-11"
                                }
                              },
                              "spec": {
                                "selector": {
                                  "matchLabels": {
                                    "app": "billing"
                                  }
                                }
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        mockServer.expect(MockRestRequestMatchers.requestTo("https://kubernetes.example/api/v1/namespaces/lt/pods"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.GET))
                .andExpect(MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andRespond(MockRestResponseCreators.withSuccess("""
                        {
                          "items": [
                            {
                              "metadata": {
                                "name": "billing-6db7f8876d-r5q4p",
                                "labels": {
                                  "app": "billing",
                                  "helpers.app.kubernetes.io/config-branch": "release/9.99.0",
                                  "helpers.app.kubernetes.io/version": "2.17.0-12"
                                }
                              },
                              "spec": {
                                "containers": [
                                  {
                                    "name": "app",
                                    "image": "registry.example/billing:1.4.5",
                                    "resources": {
                                      "requests": {
                                        "cpu": "100m",
                                        "memory": "256Mi"
                                      },
                                      "limits": {
                                        "cpu": "500m",
                                        "memory": "512Mi"
                                      }
                                    }
                                  }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = dropAppClient.fetchConfiguration(List.of("lt"), List.of(DropAppConfigurationComponent.PODS));

        mockServer.verify();
        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.serviceName()).isEqualTo("billing");
            assertThat(item.version()).isEqualTo("1.4.5");
            assertThat(item.release()).isEqualTo("2.17.0-12");
        });
    }

    private static String key(String key) {
        return "dropapp:" + key;
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
