# Tasks

## 1. Согласование и безопасная проверка

- [x] 1.1 Review/Apply получены 26.10.2026: «Диагностический файл тоже не пиши. Согласовано. Реализуй». Уточнены артефакты: INFO и файловая диагностика отключаются, WARN ошибок сохраняются. Повторное согласование уже разрешённого объёма не требуется.
- [x] 1.2 Подготовить безопасную копию pom/src/config с проверенной относительной H2 и JDK 17/Maven, подменить HTTP. Записать окружение в verification.md.

## 2. Реализация и относящиеся проверки

- [x] 2.1 Уточнить проверки двух UI-каналов READY/EMPTY, перехода PARTIAL → READY, отсутствия INFO/файла/суффикса message и положительного WARN отказа. Сохранить HTTP/snapshot/fallback-тесты.
- [x] 2.2 В settings-connections.html скрыть успешные catalog toast и подпись/дату/tooltip, сохранив обычный результат подключения, catalog и предупреждения остальных состояний.
- [x] 2.3 Удалить INFO-резюме, file writer/temp/render и добавление пути/ID из диагностического сервиса; сохранить безопасные WARN фактических отказов, исходные результаты и прежние файлы.

## 3. Verify и завершение

- [x] 3.1 В безопасной копии выполнить GrafanaCatalogDiagnosticsServiceTest, GrafanaRenderCatalogServiceTest, ConnectionTestGrafanaCatalogTest и GrafanaDeploymentClientTest, затем полный mvn test на JDK 17 и существующий src/test/js/dropapp-contracts.cjs. Зафиксировать counts и outcomes.
- [x] 3.2 Выполнить функциональный browser Verify реальной страницы подключений: READY/EMPTY/PARTIAL/STALE/UNAVAILABLE/unknown/null, одиночная/общая/save-проверка, переход ошибки в успех. Проверить сохранившийся выбор deployment, отсутствие новых файлов и неизменность seeded старого отчёта на изолированном запуске.
- [x] 3.3 Обновить README, traceability/gaps фактическими результатами; выполнить openspec validate quiet-successful-grafana-checks --strict, git diff --check и проверить границы diff.
- [x] 3.4 Представить результат владельцу. Archive/sync, commit/push, merge и deployment оставить для отдельной команды.
