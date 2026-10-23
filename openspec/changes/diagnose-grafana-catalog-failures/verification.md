# Проверка diagnose-grafana-catalog-failures

Владелец согласовал представленные proposal/specs/design/tasks и отдельно разрешил Apply 23.10.2026: «согласовано, примени». Разрешение commit/push дано предыдущим запросом. Применение и проверки завершены 23.10.2026; Archive/sync, merge и deployment не выполнялись.

## Результат и область

Добавлены безопасные метаданные ошибки catalog API, сбор результата попытки и отдельный читаемый отчёт в `logs/grafana-catalog-diagnostics-user-<id>.txt`. Существующее `catalog.message` показывает путь и UUID либо явное сообщение об отказе записи. Схема DTO, верхнеуровневые service/success/message, состояния каталога, исходные HTTP-запросы, snapshots, НФТ и рендер сохраняются.

В отчёт и новые диагностические сообщения попадают только типизированные этапы/категории, числовой HTTP-код, время/UUID/счётчики и SHA-256-ключ dashboard UID. Произвольные сообщения, URI, заголовки, тела, значения переменных и stack trace не выводятся. Подробности реализации — [design](design.md), пользовательские действия — [README](../../../README.md#диагностика-каталога-grafana).

## Изоляция и команды

- Исходный HEAD перед Apply: `8191728a9f143ca3d5226502e94720c5f656197b`, ветка `main`; удалённая main перед публикацией совпадала. Чужие untracked .agents, .gitkeep и три открытых плана сохранены.
- Безопасная копия: `/var/folders/24/wchh7vqs2r15pcnmhbpwymvm0000gn/T/perfhub-catalog-diagnostics-4v_0rh28`. Копировались только pom.xml, src и config/application.properties. Проверены относительный H2 `./config/perfhub-db`, username sa, пустой пароль и отсутствие иных конфигурационных ключей интеграций. Пользовательские БД, кеш, экспорты и логи не копировались.
- Java: Amazon Corretto **17.0.11**, Maven **3.9.10**, macOS arm64. JAVA_HOME и PATH явно указывали на JDK 17. Это прогон на JDK 17, не только release=17.
- Профильный прогон: `mvn -Dtest=GrafanaDeploymentClientTest,GrafanaCatalogDiagnosticsServiceTest,GrafanaRenderCatalogServiceTest,ConnectionTestGrafanaCatalogTest test` — **31 тест, 0 failures/errors/skipped**, BUILD SUCCESS. После добавления дополнительных проверок datasource/storage выполнен полный прогон ниже.
- Полный `mvn test` в этой копии — **187 тестов, 0 failures/errors/skipped**, BUILD SUCCESS, 14.024 секунды по Maven. Отчёты surefire и full-tests.log остались только в безопасной временной копии.
- `node src/test/js/dropapp-contracts.cjs` в копии — все три группы PASS: перенос/порядок/payload, идентификация строк, частичные ошибки/timeout/кеш и отбрасывание чужих/поздних ответов.
- Браузер: существующий Playwright с headless Google Chrome; новые зависимости проекта не устанавливались. Java harness поднял настоящее Spring/Thymeleaf-приложение с новой `./config/isolated-ui-db`, адресом 127.0.0.1 и синтетическими пользователями. Grafana подменена локальным HTTP-сервером 127.0.0.1; фоновые интеграции не настроены, опрос отложен. Разрешены только локальный UI и уже используемые CDN ресурсов; неожиданных внешних обращений нет.

## Матрица PH-CFG-007

| Сценарий | Реальная проверка | Итог / предел |
|---|---|---|
| S01 | [GrafanaDeploymentClientTest](../../../src/test/java/perfhub/client/GrafanaDeploymentClientTest.java), catalogKeepsOperationAndHttpFailureWithoutUsingResponseText: HTTP 400/401/403/404/500 для search/dashboard/deployment; realHttpFailureProducesSafeFileLogsAndConnectionResponse в [service tests](../../../src/test/java/perfhub/service/GrafanaRenderCatalogServiceTest.java) | PASS: этап, фактический код, подтип прежнего исключения, прежний GET/auth; источник синтетический |
| S02 | catalogDistinguishesInvalidHttp200FromNoResponse, catalogRejectsUnexpectedSuccessfulResponseShape | PASS: пустое тело, неверный JSON/content type/status/data сохраняют 200 как неуспешный ответ; timeout без HTTP не получает выдуманного кода |
| S03 | reportsParserAndInvalidContextWithoutPublishingSnapshot; recordsContextChangeAndRejectedLatePublication; reportsStorageFailureBeforeLoopWithoutLeakingException; [fallback test](../../../src/test/java/perfhub/service/ConnectionTestGrafanaCatalogTest.java) | PASS: parser, namespace, datasource, context=false, save=false, initialization/save exceptions и fallback диагностируются; сырой текст не выводится |
| S04 | reportsPartialPerDashboardThenReplacesItOnRecoveryWithoutExtraRequests; searchFailureCountsAsGlobalAndRetainsPreviouslyPublishedSnapshot; локальный UI | PASS: PARTIAL/STALE-поведение, счётчики отдельно для dashboard/общих ошибок; health остаётся success=true |
| S05 | replacesPreviousFailureWithSuccessfulReportWithoutHistory в [file tests](../../../src/test/java/perfhub/service/GrafanaCatalogDiagnosticsServiceTest.java); локальная проверка PARTIAL → READY; чтение редактора | PASS: новый UUID и полная замена файла, прежний HTTP 403 исчезает; открытие редактора не переписывает файл и не вызывает Grafana |
| S06 | blockedDirectoryPreservesResultAndDoesNotClaimOldReportIsCurrent; createWriteAndMoveFailuresRetainOldFileAndCleanTemporaryFiles; cleanupFailureIsSafeAndDoesNotChangeSuccessfulPublication; реальный UI с logs, временно заменённым файлом-блокером | PASS: create/write/move/cleanup fail не меняют health/status/updatedAt; UI явно сообщает отказ записи, старый отчёт не объявляется новым |
| S07 | realHttpFailureProducesSafeFileLogsAndConnectionResponse; unknownUserDoesNotOverwriteGlobalFileAndOnlyAllowlistedFieldsReachLogs; HTTP тесты и файл локального UI | PASS: синтетические маркеры из token/URI/selector/namespace/title/UID/upstream body/cause/control characters отсутствуют в полном файле, новых captured SLF4J events и JSON. ThrowableProxy отсутствует. Это не аудит общего логирования прочих интеграций |
| S08 | boundsReportAndCountsEveryOmittedFailure; concurrentChecksPublishWholeSeparateReportsForEachUser; два пользователя локального UI | PASS: 1000 ошибок → 100 записей + 900 пропусков, размер ≤128 КиБ; 24 параллельные публикации дают целые разные файлы двух пользователей, временные файлы удалены. Несколько JVM с одной папкой не проверялись и не входят в область |
| S09 | JSON assertions в realHttpFailureProducesSafeFileLogsAndConnectionResponse, существующие catalog/DropApp/render/cache tests, JS contracts, полный Maven; счётчик локального HTTP-источника | PASS: на явную проверку ровно health + search + два dashboard + один deployment запрос; новые корпоративные запросы не добавлены. Схема JSON и верхнеуровневые сообщения сохранены |

## Браузер и визуальная проверка

**13 checkpoints PASS**, browser-results.json и снимки сохранены в той же временной копии:

1. Guest API остаётся 401.
2. Thymeleaf страницы подключений и активная навигация; открытие страницы не вызывает Grafana.
3. PARTIAL с настоящим синтетическим HTTP 403: файл, совпадающий UUID, видимое уведомление и title статуса.
4. Точное прежнее количество/набор запросов источнику.
5. Повторная проверка READY заменяет файл; доступны CPU/RAM и два конкретных deployment.
6. Чтение шаблонов использует локальный каталог; файл и счётчик источника не меняются.
7. Sidebar на 992 px, кнопка collapse скрыта.
8. Sidebar на 993 px, кнопка collapse видна.
9. Collapse/restore работают.
10. Второй ADMIN получает отдельный файл и не меняет первый.
11. USER имеет прежний доступ к настройкам, скрыт admin-пункт; admin API возвращает 403.
12. Ошибка записи видна в UI, health и catalog READY сохранены; прежний файл не выдан за новый.
13. Нет ошибок JavaScript и неожиданных внешних запросов.

Проверены открытие/сохранение формы Grafana, модальное окно и уведомления. Снимки connections-partial.png, connections-992.png, connections-993.png просмотрены визуально: текст уведомления и длинный путь переносятся и читаются.

**Известные ограничения прежней разметки:** у таблицы подключений тесные/перекрывающиеся длинные заголовки при 993 px; при 992 px sidebar занимает отдельный верхний блок перед контентом. В этой работе HTML/CSS/JS побайтно совпадают с HEAD, эти особенности не исправлялись. Настоящий корпоративный renderer повторно не поднимался: catalog-only helper не изменяет путь PNG, его регрессионные тесты прошли.

## Границы доказательств и откат

Корпоративные API, реальные datasource-права, selector и пользовательская БД не исследовались. Причина исходного корпоративного отказа остаётся D до получения нового отчёта владельца; синтетический HTTP 403 не объявляется его причиной. G-01 и G-22 остаются открытыми. Файлы диагностики не являются входными данными приложения и не попадают в Git (существующий ignore logs/).

Откат возвращает код/документацию предыдущей версии без миграции БД/snapshot/кеша. Основные specs и другие планы не менялись. Строгая валидация OpenSpec, локальные ссылки и границы diff проверяются перед публикацией; это проверка артефактов, не функциональный тест.
