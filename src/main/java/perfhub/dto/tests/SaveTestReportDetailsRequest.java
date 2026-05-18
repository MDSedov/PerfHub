package com.example.chronicler.dto.tests;

/** Запрос на сохранение полей теста, используемых в отчете по релизу. */
public record SaveTestReportDetailsRequest(String reportStatus, String reportSummary) {}
