package com.example.chronicler.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.example.chronicler.service.ConfigurationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    private static final Logger log = LoggerFactory.getLogger(RestTemplateConfig.class);

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder, ConfigurationProvider configurationProvider) {
        String trustStorePath =
                configurationProvider.findValue("gigachat", "trust-store").map(String::trim).orElse("");
        String trustStorePassword =
                configurationProvider.findValue("gigachat", "trust-store-password").orElse("");
        String trustStoreType =
                configurationProvider.findValue("gigachat", "trust-store-type").orElse("");
        boolean disableSslValidation = configurationProvider
                .findValue("gigachat", "disable-ssl-validation")
                .map(String::trim)
                .map(Boolean::parseBoolean)
                .orElse(false);

        if (disableSslValidation) {
            log.warn("Disabling SSL validation for RestTemplate. Do not enable in production.");
            try {
                HttpClient httpClient = createTrustAllHttpClient();
                return buildWith(builder, httpClient);
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException("Failed to disable SSL validation", ex);
            }
        }

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
            HttpClient httpClient = createTrustStoreBackedHttpClient(inputStream, effectiveType, password);
            return buildWith(builder, httpClient);
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to configure RestTemplate trust store", ex);
        }
    }

    private static RestTemplate buildWith(RestTemplateBuilder builder, HttpClient httpClient) {
        RestTemplate restTemplate = builder.build();
        restTemplate.setRequestFactory(new JdkClientHttpRequestFactory(httpClient));
        return restTemplate;
    }

    private static HttpClient createTrustAllHttpClient() throws GeneralSecurityException {
        TrustManager[] trustAllManagers = new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // Trust all
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // Trust all
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllManagers, new SecureRandom());

        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm(null);

        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .sslParameters(sslParameters)
                .build();
    }

    private static HttpClient createTrustStoreBackedHttpClient(
            InputStream trustStoreInputStream, String type, char[] password)
            throws GeneralSecurityException, IOException {
        KeyStore trustStore = KeyStore.getInstance(type);
        trustStore.load(trustStoreInputStream, password);

        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());

        return HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();
    }
}
