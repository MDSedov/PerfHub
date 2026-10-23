# Проверка fallback-to-grafana-deployment-resources

## Разрешение и окружение

Владелец 23.10.2026: «согласовано, примени fallback-to-grafana-deployment-resources». Review всех четырёх артефактов и Apply получены. Git результата разрешён прежней командой; Archive/merge/deployment не запрошены.

Временный корень: `/var/folders/24/wchh7vqs2r15pcnmhbpwymvm0000gn/T/perfhub-grafana-resources-z_4b3jk8`. Скопированы только `pom.xml`, `src/` и `config/application.properties`. До копирования подтверждены относительный `jdbc:h2:file:./config/perfhub-db`, пустой пароль тестового sa и отсутствие других ключей помимо datasource/sql.init. Пользовательские БД, кеши, логи и экспорты не копировались. Новая файловая H2 создаётся только во временном корне; JDBC unit-тесты используют уникальные in-memory H2. HTTP — MockRestServiceServer; корпоративные источники не вызываются.

JDK: Amazon Corretto 17.0.11; Maven 3.9.10, macOS arm64. Перед Maven установлены JAVA_HOME и PATH этого JDK, версия подтверждена командами java -version / mvn -version. Production-конфигурация не меняется.

## Ход проверки

Новые клиентские тесты выполнены до изменения production-кода: `mvn -B -Dstyle.color=never -Dtest=GrafanaDeploymentClientTest test` — 20 выполнений, 9 failures, 2 errors; отказы соответствуют отсутствующему переходу после 404. После изменения тот же набор: **20/20, failures/errors/skipped = 0**, 3,947 с. Логи `client-before.log` и `client-after.log` находятся во временном корне.

Проверены ровно два чтения после proxy 404, отсутствие третьего, исходный успешный/пустой ответ без resources, HTTP 400/401/403/404/429/500, timeout, пустой/не-JSON/неподдержанный envelope и нестроковый data. При успешном HTTP 200 с неправильным форматом сохраняется код 200; при timeout кода нет. Параметры GET, UID, base prefix, auth и буквальный selector со специальными символами совпадают. Даже изменение configuration provider между ответами не меняет base URL и bearer одной операции. Существующие тесты основного HTTP/renderer сохранены, ожидание окончательного 404 дополнено новым этапом.

Профильный прогон `mvn -B -Dstyle.color=never -Dtest=GrafanaDeploymentClientTest,GrafanaRenderCatalogServiceTest,GrafanaCatalogDiagnosticsServiceTest,ConnectionTestGrafanaCatalogTest test`: **46/46, failures/errors/skipped = 0**, 7,922 с. После усиления проверки секретов промежуточного proxy-ответа выполнен полный `mvn -B -Dstyle.color=never test`: **202/202, failures/errors/skipped = 0**, 15,153 с, JDK 17. Логи `targeted.log`, `full.log` и surefire XML находятся во временном корне; это локальные временные доказательства, не пользовательские данные.

`node src/test/js/dropapp-contracts.cjs`: три группы PASS — export/import/order, настоящий save payload, per-row/partial/timeout/cache/reopen. JS не менялся. `openspec validate fallback-to-grafana-deployment-resources --strict` и `git diff --check` успешны. Структурная валидация не заменяет функциональные тесты.

## Матрица PH-GRAF-010

| Сценарий | Фактическое доказательство | Результат |
|---|---|---|
| S01 | `GrafanaDeploymentClientTest.proxy404UsesResourcesOnceWithTheSameLiteralSelectorAndContext`; `GrafanaRenderCatalogServiceTest.resourcesRecoveryPublishesFilteredOptionsThenEmptyWithoutRequestsOnLocalRead` | Два GET, те же UID/selector/auth/base prefix; изменение provider между ответами не меняет транспортный контекст. H2 snapshot READY, All/дубли исключены, варианты отсортированы |
| S02 | Прежний `labelValuesUsesSelectorLiterallyWithoutSendingLabelValuesAsPromql`, новый `emptySuccessUsesOnlyTheSelectedRoute` | Успешный основной ответ не вызывает resources; успешный пустой ответ не заменён текущим deployment |
| S03 | `catalogKeepsOperationAndHttpFailureWithoutUsingResponseText`, `catalogDistinguishesInvalidHttp200FromNoResponse`, `catalogRejectsUnexpectedSuccessfulResponseShape` | Основные 400/401/403/429/500, timeout, invalid 200 без дополнительного запроса. Search/load 404 также без resources |
| S04 | `resourcesHttpFailureKeepsItsOwnStatusWithoutAThirdRequest`, `resourcesRejectsEmptyMalformedAndUnexpectedSuccessfulResponses`, `resourcesTimeoutKeepsNoHttpStatusWithoutAThirdRequest`; service `resourcesFailureCountsDashboardOnceForUnavailableAndPartialCatalogs`, `resourcesFailuresKeepStaleCatalogAndSafeTerminalDiagnostics` | Нет третьего вызова. Окончательные HTTP 400/401/403/404/429/500, invalid 200 и timeout сохраняют этап/код/null. Один отказ dashboard, старый список сохранён; UNAVAILABLE/PARTIAL/STALE подтверждены |
| S05 | Client `emptySuccessUsesOnlyTheSelectedRoute`; service `resourcesRecoveryPublishesFilteredOptionsThenEmptyWithoutRequestsOnLocalRead`; прежние `ConfluenceTemplateDeploymentTest` | Пустой resources envelope даёт актуальный пустой snapshot и EMPTY без промежуточного 404 в отчёте. Полный набор сохраняет прежние исторические строки/выбор; их код не менялся |
| S06 | Service `resourcesFailuresKeepStaleCatalogAndSafeTerminalDiagnostics`, `resourcesRecoveryStaysReadyWhenDiagnosticDirectoryIsBlocked`; прежние семь `GrafanaCatalogDiagnosticsServiceTest` | Секреты обоих ответов, URI/selector/token и поддельные строки отсутствуют в файле/новых диагностических логах/ConnectionResponse; throwable не логируется. HTTP 403/invalid 200/timeout различимы. Недоступный файл сохраняет READY и сообщает отказ записи |
| S07 | Service `resourcesSuccessDoesNotPublishAfterNamespaceOrRevisionChanges`, `resourcesRecoveryPublishesFilteredOptionsThenEmptyWithoutRequestsOnLocalRead`, прежние owner/context/read-only тесты; полный Maven и JS | Поздний namespace/revision не публикует snapshot; чужой owner не читает результат; локальное чтение не вызывает HTTP и не заменяет отчёт. Рендер/cache/НФТ/ordinary regression tests прошли |

Новые service-тесты используют настоящий GrafanaClient, MockRestServiceServer, настоящий service/repository/H2 и запись файла. Парсер и сохранённые переменные подменены существующими обезличенными fixtures: это не проверка реального dashboard JSON, корпоративных прав или доступности resources.

## Границы и ограничения

Production-diff: только `GrafanaClient.loadDeploymentLabelValues`, overload существующего чтения JSON для удержания одного bearer в двух запросах и константа внутреннего этапа. Таймауты, другие интеграционные операции, URL/методы/JSON PerfHub, SQL, production-конфигурация, зависимости, HTML/CSS/JS и основные specs не менялись. Существующее диагностическое изменение и прочие незавершённые планы не переписывались. UI-проход не выполнялся: интерфейс в этом изменении не затронут.

Побайтовая сверка всех `src/`, pom и config с исполненной временной копией успешна; surefire XML подтверждают 33 тестовых класса / 202 выполнения / 0 ошибок, падений и пропусков. В подготовленных документах проверена 471 локальная файловая ссылка; имена тестовых методов в матрице сверены с исходниками. `git diff --exit-code -- pom.xml config src/main/resources openspec/specs openspec/changes/diagnose-grafana-catalog-failures` подтвердил сохранение границ. Новые requirement/scenario ID: один PH-GRAF-010 и семь последовательных S01…07, основной baseline 50/134 не меняется.

**A:** ограниченная совместимость реализована и проверена на синтетическом HTTP. **D:** первопричина корпоративного 404 и результат resources на стенде владельца ещё не установлены. Проверка владельца — обновить код, перезапустить PerfHub, выполнить прежнюю проверку Grafana и открыть редактор; при отказе используется новый файл с окончательным этапом. Наличие зелёных локальных тестов не закрывает корпоративный G-24.

Откат — возврат двух production-файлов к версии до изменения; миграция/удаление snapshot и изменение настроек не требуются. Archive/sync/merge/deployment не выполнялись.
