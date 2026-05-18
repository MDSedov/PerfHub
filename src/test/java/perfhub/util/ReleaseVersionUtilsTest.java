package perfhub.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseVersionUtilsTest {

    @Test
    void findMaxReleaseReturnsGreatestNumericRelease() {
        String release = ReleaseVersionUtils.findMaxRelease(List.of("2.11.0", "2.12.0", "2.12.1"));

        assertThat(release).isEqualTo("2.12.1");
    }

    @Test
    void findMaxReleaseComparesNumericSegmentsNumerically() {
        String release = ReleaseVersionUtils.findMaxRelease(List.of("2.12.2", "2.12.10", "2.12.9"));

        assertThat(release).isEqualTo("2.12.10");
    }

    @Test
    void compareTreatsReleaseWithoutQualifierAsHigher() {
        int comparison = ReleaseVersionUtils.compare("2.12.1", "2.12.1-rc1");

        assertThat(comparison).isPositive();
    }
}
