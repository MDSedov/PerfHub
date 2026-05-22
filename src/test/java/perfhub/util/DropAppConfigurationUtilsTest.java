package perfhub.util;

import static org.assertj.core.api.Assertions.assertThat;

import perfhub.dto.dropapp.DropAppConfigurationItemDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class DropAppConfigurationUtilsTest {

    @Test
    void groupPodItemsCombinesPodsByServiceAndContainer() {
        List<DropAppConfigurationItemDto> result = DropAppConfigurationUtils.groupPodItems(List.of(
                new DropAppConfigurationItemDto(
                        "lt",
                        null,
                        "egressgateway-eprofile-lt-85d68d75d7-7gd4",
                        "gateway",
                        "1.3.0",
                        "2.12.7",
                        "100m",
                        "500m",
                        "256Mi",
                        "512Mi"),
                new DropAppConfigurationItemDto(
                        "lt",
                        null,
                        "egressgateway-eprofile-lt-85d68d75d7-ggtx",
                        "gateway",
                        "1.3.0",
                        "2.12.7",
                        "100m",
                        "500m",
                        "256Mi",
                        "512Mi")));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.serviceName()).isEqualTo("egressgateway-eprofile-lt");
            assertThat(item.podCount()).isEqualTo(2);
            assertThat(item.containerName()).isEqualTo("gateway");
            assertThat(item.version()).isEqualTo("1.3.0");
            assertThat(item.release()).isEqualTo("2.12.7");
            assertThat(item.requestCpu()).isEqualTo("100m");
            assertThat(item.limitCpu()).isEqualTo("500m");
            assertThat(item.requestRam()).isEqualTo("256Mi");
            assertThat(item.limitRam()).isEqualTo("512Mi");
        });
    }

    @Test
    void groupPodItemsDoesNotUseContainerVersionAsReleaseFallback() {
        List<DropAppConfigurationItemDto> result = DropAppConfigurationUtils.groupPodItems(List.of(
                new DropAppConfigurationItemDto(
                        "lt",
                        null,
                        "billing-85d68d75d7-7gd4",
                        "app",
                        "2.17.0-12",
                        "",
                        "100m",
                        "500m",
                        "256Mi",
                        "512Mi")));

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.version()).isEqualTo("2.17.0-12");
            assertThat(item.release()).isNull();
        });
    }
}
