package perfhub.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class VmDataCache {

    private final AtomicReference<CachedVmData> cachedData = new AtomicReference<>();

    public void store(List<String> types, Instant start, Instant end, String payload) {
        cachedData.set(new CachedVmData(List.copyOf(types), start, end, payload));
    }

    public Optional<CachedVmData> getLatest() {
        return Optional.ofNullable(cachedData.get());
    }

    public void clear() {
        cachedData.set(null);
    }

    public record CachedVmData(List<String> types, Instant start, Instant end, String payload) {}
}
