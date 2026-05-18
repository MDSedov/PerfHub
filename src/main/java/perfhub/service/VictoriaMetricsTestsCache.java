package perfhub.service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class VictoriaMetricsTestsCache {

    private final AtomicReference<String> cachedResponse = new AtomicReference<>();

    public void store(String response) {
        cachedResponse.set(response);
    }

    public Optional<String> getLatest() {
        return Optional.ofNullable(cachedResponse.get());
    }

    public void clear() {
        cachedResponse.set(null);
    }
}
