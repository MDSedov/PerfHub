package perfhub.util;

import perfhub.dto.dropapp.DropAppConfigurationItemDto;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** Helpers for presenting DropApp pod resources as service-level configuration rows. */
public final class DropAppConfigurationUtils {

    private static final Pattern REPLICA_SET_HASH_PATTERN = Pattern.compile("[a-z0-9]{8,12}");
    private static final Pattern POD_SUFFIX_PATTERN = Pattern.compile("[a-z0-9]{4,6}");
    private static final Set<String> POD_COMPONENT_LABELS = Set.of("pod", "pods");

    private DropAppConfigurationUtils() {}

    public static List<DropAppConfigurationItemDto> groupPodItems(Collection<DropAppConfigurationItemDto> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }

        Map<ServiceKey, ServiceAccumulator> serviceAccumulators = new LinkedHashMap<>();
        List<DropAppConfigurationItemDto> otherItems = new ArrayList<>();

        for (DropAppConfigurationItemDto item : items) {
            if (item == null) {
                continue;
            }
            if (!isPodResourceItem(item)) {
                otherItems.add(item);
                continue;
            }
            String serviceName = resolveServiceName(item.serviceName(), item.podName());
            ServiceKey serviceKey = new ServiceKey(item.namespace(), serviceName);
            serviceAccumulators
                    .computeIfAbsent(serviceKey, ignored -> new ServiceAccumulator(item.namespace(), serviceName))
                    .add(item);
        }

        List<DropAppConfigurationItemDto> result = new ArrayList<>();
        serviceAccumulators.values().stream()
                .sorted(Comparator
                        .comparing(ServiceAccumulator::namespace, DropAppConfigurationUtils::compareNullableText)
                        .thenComparing(ServiceAccumulator::serviceName, DropAppConfigurationUtils::compareNullableText))
                .forEach(accumulator -> result.addAll(accumulator.toRows()));
        result.addAll(otherItems);
        return result;
    }

    public static boolean isPodResourceItem(DropAppConfigurationItemDto item) {
        if (item == null) {
            return false;
        }
        return isPositive(item.podCount())
                || isPodComponentLabel(item.serviceName())
                || StringUtils.hasText(item.requestCpu())
                || StringUtils.hasText(item.limitCpu())
                || StringUtils.hasText(item.requestRam())
                || StringUtils.hasText(item.limitRam());
    }

    public static String resolveServiceName(String serviceName, String podName) {
        String normalizedServiceName = normalize(serviceName);
        if (normalizedServiceName != null && !isPodComponentLabel(normalizedServiceName)) {
            return normalizedServiceName;
        }
        return deriveServiceNameFromPodName(podName);
    }

    public static String deriveServiceNameFromPodName(String podName) {
        String normalizedPodName = normalize(podName);
        if (normalizedPodName == null) {
            return null;
        }

        String[] parts = normalizedPodName.split("-");
        if (parts.length >= 3
                && isReplicaSetHash(parts[parts.length - 2])
                && isPodSuffix(parts[parts.length - 1])) {
            return String.join("-", List.of(parts).subList(0, parts.length - 2));
        }
        return normalizedPodName;
    }

    public static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static boolean isPodComponentLabel(String value) {
        String normalized = normalize(value);
        return normalized != null && POD_COMPONENT_LABELS.contains(normalized.toLowerCase(Locale.ROOT));
    }

    private static boolean isPositive(Integer value) {
        return value != null && value > 0;
    }

    private static boolean isReplicaSetHash(String value) {
        return value != null && REPLICA_SET_HASH_PATTERN.matcher(value).matches();
    }

    private static boolean isPodSuffix(String value) {
        return value != null && POD_SUFFIX_PATTERN.matcher(value).matches();
    }

    private static int compareNullableText(String left, String right) {
        if (left == null) {
            return right == null ? 0 : -1;
        }
        if (right == null) {
            return 1;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(left, right);
    }

    private record ServiceKey(String namespace, String serviceName) {}

    private static final class ServiceAccumulator {
        private final String namespace;
        private final String serviceName;
        private final Set<String> podNames = new LinkedHashSet<>();
        private final Map<ContainerKey, DropAppConfigurationItemDto> containerRows = new LinkedHashMap<>();
        private final List<String> releases = new ArrayList<>();
        private int declaredPodCount;

        private ServiceAccumulator(String namespace, String serviceName) {
            this.namespace = namespace;
            this.serviceName = serviceName;
        }

        private String namespace() {
            return namespace;
        }

        private String serviceName() {
            return serviceName;
        }

        private void add(DropAppConfigurationItemDto item) {
            String podName = normalize(item.podName());
            if (podName != null) {
                podNames.add(podName);
            }
            if (item.podCount() != null && item.podCount() > declaredPodCount) {
                declaredPodCount = item.podCount();
            }
            if (StringUtils.hasText(item.release())) {
                releases.add(item.release());
            }

            ContainerKey key = new ContainerKey(
                    item.containerName(),
                    item.version(),
                    item.requestCpu(),
                    item.limitCpu(),
                    item.requestRam(),
                    item.limitRam());
            containerRows.putIfAbsent(key, item);
        }

        private List<DropAppConfigurationItemDto> toRows() {
            Integer podCount = resolvePodCount();
            String release = resolveRelease();
            return containerRows.values().stream()
                    .map(item -> new DropAppConfigurationItemDto(
                            namespace,
                            serviceName,
                            null,
                            podCount,
                            item.containerName(),
                            item.version(),
                            release,
                            item.requestCpu(),
                            item.limitCpu(),
                            item.requestRam(),
                            item.limitRam()))
                    .sorted(Comparator
                            .comparing(DropAppConfigurationItemDto::containerName, DropAppConfigurationUtils::compareNullableText)
                            .thenComparing(DropAppConfigurationItemDto::version, DropAppConfigurationUtils::compareNullableText))
                    .toList();
        }

        private Integer resolvePodCount() {
            int resolved = Math.max(podNames.size(), declaredPodCount);
            return resolved > 0 ? resolved : null;
        }

        private String resolveRelease() {
            return ReleaseVersionUtils.findMaxRelease(releases);
        }
    }

    private record ContainerKey(
            String containerName,
            String version,
            String requestCpu,
            String limitCpu,
            String requestRam,
            String limitRam) {}
}
