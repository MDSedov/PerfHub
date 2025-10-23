package com.example.chronicler.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    private static final Logger log = LoggerFactory.getLogger(RestTemplateConfig.class);

    @Bean
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            @Value("${gigachat.trust-store:}") String trustStorePath,
            @Value("${gigachat.trust-store-password:}") String trustStorePassword,
            @Value("${gigachat.trust-store-type:}") String trustStoreType) {
        if (!StringUtils.hasText(trustStorePath)) {
            return builder.build();
        }

        String effectiveType = StringUtils.hasText(trustStoreType)
                ? trustStoreType
                : KeyStore.getDefaultType();
        char[] password = StringUtils.hasText(trustStorePassword)
                ? trustStorePassword.toCharArray()
                : null;

        log.info(
                "Configuring RestTemplate to use trust store at '{}' with type '{}'",
                trustStorePath,
                effectiveType);

        try (InputStream inputStream = Files.newInputStream(Path.of(trustStorePath))) {
            KeyStore trustStore = KeyStore.getInstance(effectiveType);
            trustStore.load(inputStream, password);

            HttpClient httpClient = HttpClients.custom()
                    .setSSLContext(new SSLContextBuilder().loadTrustMaterial(trustStore, null).build())
                    .build();

            HttpComponentsClientHttpRequestFactory requestFactory =
                    new HttpComponentsClientHttpRequestFactory(httpClient);

            return builder.requestFactory(() -> requestFactory).build();
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to configure RestTemplate trust store", ex);
        }
    }
}
