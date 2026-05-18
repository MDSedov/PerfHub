package perfhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PerfHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(PerfHubApplication.class, args);
    }
}
