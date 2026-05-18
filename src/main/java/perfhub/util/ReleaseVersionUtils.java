package perfhub.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Утилиты для нормализации и сравнения версий релизов. */
public final class ReleaseVersionUtils {

    private static final Pattern RELEASE_SEGMENT_SEPARATOR = Pattern.compile("[._-]+");

    private ReleaseVersionUtils() {}

    public static String findMaxRelease(Collection<String> releases) {
        if (releases == null || releases.isEmpty()) {
            return null;
        }
        return releases.stream()
                .map(ReleaseVersionUtils::normalize)
                .filter(Objects::nonNull)
                .max(ReleaseVersionUtils::compare)
                .orElse(null);
    }

    public static int compare(String left, String right) {
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft == null) {
            return normalizedRight == null ? 0 : -1;
        }
        if (normalizedRight == null) {
            return 1;
        }

        List<String> leftSegments = splitIntoSegments(normalizedLeft);
        List<String> rightSegments = splitIntoSegments(normalizedRight);
        int maxSegments = Math.max(leftSegments.size(), rightSegments.size());
        for (int index = 0; index < maxSegments; index++) {
            String leftSegment = index < leftSegments.size() ? leftSegments.get(index) : null;
            String rightSegment = index < rightSegments.size() ? rightSegments.get(index) : null;
            int comparison = compareSegments(leftSegment, rightSegment);
            if (comparison != 0) {
                return comparison;
            }
        }

        int comparison = String.CASE_INSENSITIVE_ORDER.compare(normalizedLeft, normalizedRight);
        return comparison != 0 ? comparison : normalizedLeft.compareTo(normalizedRight);
    }

    public static String normalize(String release) {
        if (release == null) {
            return null;
        }
        String normalized = release.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static List<String> splitIntoSegments(String release) {
        String[] rawSegments = RELEASE_SEGMENT_SEPARATOR.split(release);
        List<String> segments = new ArrayList<>(rawSegments.length);
        for (String rawSegment : rawSegments) {
            String normalized = normalize(rawSegment);
            if (normalized != null) {
                segments.add(normalized);
            }
        }
        return segments;
    }

    private static int compareSegments(String leftSegment, String rightSegment) {
        if (leftSegment == null) {
            return compareMissingSegment(rightSegment);
        }
        if (rightSegment == null) {
            return -compareMissingSegment(leftSegment);
        }

        boolean leftNumeric = isNumericSegment(leftSegment);
        boolean rightNumeric = isNumericSegment(rightSegment);
        if (leftNumeric && rightNumeric) {
            return compareNumericSegments(leftSegment, rightSegment);
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? 1 : -1;
        }

        int comparison = String.CASE_INSENSITIVE_ORDER.compare(leftSegment, rightSegment);
        return comparison != 0 ? comparison : leftSegment.compareTo(rightSegment);
    }

    private static int compareMissingSegment(String actualSegment) {
        if (actualSegment == null) {
            return 0;
        }
        if (isNumericSegment(actualSegment)) {
            return isZeroSegment(actualSegment) ? 0 : -1;
        }
        return 1;
    }

    private static int compareNumericSegments(String leftSegment, String rightSegment) {
        String normalizedLeft = stripLeadingZeros(leftSegment);
        String normalizedRight = stripLeadingZeros(rightSegment);
        int lengthComparison = Integer.compare(normalizedLeft.length(), normalizedRight.length());
        if (lengthComparison != 0) {
            return lengthComparison;
        }
        return normalizedLeft.compareTo(normalizedRight);
    }

    private static boolean isNumericSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            return false;
        }
        for (int index = 0; index < segment.length(); index++) {
            if (!Character.isDigit(segment.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isZeroSegment(String segment) {
        return "0".equals(stripLeadingZeros(segment));
    }

    private static String stripLeadingZeros(String value) {
        int index = 0;
        while (index < value.length() - 1 && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }
}
