package com.example.chronicler.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DropAppKeepAliveSchedulerTest {

    @Mock
    private DropAppService dropAppService;

    @Mock
    private BackgroundErrorService backgroundErrorService;

    private DropAppKeepAliveScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DropAppKeepAliveScheduler(dropAppService, backgroundErrorService);
    }

    @Test
    void keepAliveSkipsRequestWhenDropAppIsNotConfigured() {
        when(dropAppService.isConfigured()).thenReturn(false);

        scheduler.keepAlive();

        verify(dropAppService, never()).keepAlive();
        verify(backgroundErrorService, never()).publish(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void keepAliveCallsDropAppWhenConfigurationIsPresent() {
        when(dropAppService.isConfigured()).thenReturn(true);

        scheduler.keepAlive();

        verify(dropAppService).keepAlive();
        verify(backgroundErrorService, never()).publish(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void keepAlivePublishesBackgroundErrorWhenRequestFails() {
        when(dropAppService.isConfigured()).thenReturn(true);
        doThrow(new IllegalStateException("DropApp unavailable")).when(dropAppService).keepAlive();

        scheduler.keepAlive();

        verify(backgroundErrorService).publish("Не удалось выполнить keep-alive запрос к DropApp: DropApp unavailable");
    }
}
