package perfhub.service;

import java.util.Optional;

public interface ConfigurationProvider {

    String getRequiredValue(String service, String key);

    Optional<String> findValue(String service, String key);
}
