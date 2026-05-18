package perfhub.service;

import perfhub.exception.ConfluenceOperationException;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConfluenceRequestThrottler {

    private final long reportParentPagesRequestIntervalMs;
    private final Object reportParentPagesMonitor = new Object();
    private long nextReportParentPagesRequestAtMs;

    public ConfluenceRequestThrottler(
            @Value("${confluence.report-parent-pages.request-interval-ms:1000}") long reportParentPagesRequestIntervalMs) {
        this.reportParentPagesRequestIntervalMs = Math.max(0L, reportParentPagesRequestIntervalMs);
    }

    public void awaitReportParentPagesRequestSlot() {
        if (reportParentPagesRequestIntervalMs == 0L) {
            return;
        }

        synchronized (reportParentPagesMonitor) {
            long now = System.currentTimeMillis();
            long waitMs = nextReportParentPagesRequestAtMs - now;
            if (waitMs > 0L) {
                sleep(waitMs);
            }
            nextReportParentPagesRequestAtMs = System.currentTimeMillis() + reportParentPagesRequestIntervalMs;
        }
    }

    private void sleep(long waitMs) {
        try {
            TimeUnit.MILLISECONDS.sleep(waitMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ConfluenceOperationException("Загрузка страниц Confluence была прервана", ex);
        }
    }
}
