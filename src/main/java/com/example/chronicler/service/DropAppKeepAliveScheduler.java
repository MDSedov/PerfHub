package com.example.chronicler.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class DropAppKeepAliveScheduler {

    private static final Logger log = LoggerFactory.getLogger(DropAppKeepAliveScheduler.class);

    private final DropAppService dropAppService;
    private final BackgroundErrorService backgroundErrorService;

    public DropAppKeepAliveScheduler(
            DropAppService dropAppService, BackgroundErrorService backgroundErrorService) {
        this.dropAppService = dropAppService;
        this.backgroundErrorService = backgroundErrorService;
    }

    @Scheduled(
            fixedDelayString = "${dropapp.keep-alive-delay-ms:300000}",
            initialDelayString = "${dropapp.keep-alive-initial-delay-ms:300000}")
    public void keepAlive() {
        if (!dropAppService.isConfigured()) {
            log.debug("Пропуск keep-alive DropApp: не настроены base-url или token");
            return;
        }

        try {
            dropAppService.keepAlive();
            log.debug("Выполнен keep-alive запрос к DropApp");
        } catch (Exception ex) {
            String message = "Не удалось выполнить keep-alive запрос к DropApp";
            String details = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
            log.warn("{}: {}", message, details);
            if (log.isDebugEnabled()) {
                log.debug("DropApp keep-alive error details", ex);
            }
            backgroundErrorService.publish(message + ": " + details);
        }
    }
}
