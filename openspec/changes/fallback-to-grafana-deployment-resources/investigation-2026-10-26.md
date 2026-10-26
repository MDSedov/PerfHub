# Диагностика deployment по фотографиям от 26.10.2026

Исторический отчёт об устранённом отказе. Упомянутые ниже шаги с диагностическим файлом и ID относятся к версии до последующего изменения: по [новому согласованному поведению](../archive/2026-10-26-quiet-successful-grafana-checks/specs/connection-settings/spec.md) файлы больше не создаются и ID не добавляется к сообщению. Подтверждение владельца об исправлении приведено ниже.

Идентификаторы datasource и namespace в этом документе обезличены при подготовке публикации: current-source-uid, stale-source-uid и demo-namespace обозначают разные значения из материалов владельца. Сохранены наблюдаемое несовпадение UID и последующее подтверждение работоспособности.

Статус: исследование текущего отказа. Новое изменение поведения не предложено; реализация приложения, тесты, настройки и основные specs не изменены. Ранее согласованный fallback уже находится в HEAD `2008268dd97fd91c3d5316cc201dfc7670ca537a`. Настоящий документ не объявляет корпоративную проблему устранённой.

## A — подтверждённые наблюдения

Прикреплены пять фотографий. Отдельного текстового отчёта и JSON-экспорта dashboard среди доступных вложений нет. На фото отчёта видны PARTIAL, восемь обновлённых dashboard, один отказ, ноль deployment, этап `DEPLOYMENT_VALUES`, категория `HTTP_ERROR`, код 404. На других фото Grafana 13.0.1 показывает варианты deployment и графики под пользовательской сессией. Сам факт этого успеха не проверяет запрос с bearer-токеном PerfHub.

В [GrafanaClient.loadDeploymentLabelValues](../../../src/main/java/perfhub/client/GrafanaClient.java) действующая последовательность следующая:

1. GET `/api/datasources/proxy/uid/<uid>/api/v1/label/deployment/values` с `match[]`.
2. Только после `HTTP_ERROR / 404` — один GET `/api/datasources/uid/<uid>/resources/api/v1/label/deployment/values` с теми же UID, selector и авторизацией.
3. Ошибка второго запроса получает этап `DEPLOYMENT_VALUES_RESOURCES`. При его успехе исходный 404 не становится итоговой ошибкой dashboard.

[GrafanaRenderCatalogService.Attempt.failure](../../../src/main/java/perfhub/service/GrafanaRenderCatalogService.java) сохраняет этап клиентского исключения, [GrafanaCatalogDiagnosticsService.render](../../../src/main/java/perfhub/service/GrafanaCatalogDiagnosticsService.java) выводит его без переименования. Поэтому итог `DEPLOYMENT_VALUES / HTTP_ERROR / 404` на фотографии не соответствует обычному выполнению этого метода в текущем коде.

Официальные исходники **Grafana v13.0.1** дополнительно проверены:

- [datasource.ts, metadataRequest](https://github.com/grafana/grafana/blob/v13.0.1/packages/grafana-prometheus/src/datasource.ts#L340-L371): Prometheus metadata-запросы направляются через datasource resources.
- [api.go, datasource routes](https://github.com/grafana/grafana/blob/v13.0.1/pkg/api/api.go#L420-L423): зарегистрированы и resources, и proxy/uid. Утверждение «в Grafana 13 удалён proxy API» этими исходниками не подтверждается.
- [language_provider.ts, getResourceClient](https://github.com/grafana/grafana/blob/v13.0.1/packages/grafana-prometheus/src/language_provider.ts#L142-L160) и [datasource.ts, hasLabelsMatchAPISupport](https://github.com/grafana/grafana/blob/v13.0.1/packages/grafana-prometheus/src/datasource.ts#L221-L239): выбор между LabelsApiClient и SeriesApiClient зависит от версии/настроек datasource. По фото фактически выбранный browser-запрос установить нельзя.

## C / D — ограничения и проверяемые гипотезы

- **D:** запущены прежние классы/JAR, другая копия проекта либо показан отчёт другой проверки. Это первая гипотеза из-за расхождения этапов, но версия локального процесса пользователя пока неизвестна.
- **D:** неверный UID datasource или устаревшее сохранённое значение. Текущий сервис подставляет `$datasource`/`${datasource}` из объединённых dashboard.current и сохранённых настроек; сохранённые значения имеют приоритет. Проверки/разрешения имени в UID нет. Надпись `prometheus` в браузере сама по себе не устанавливает значение UID.
- **D:** различие browser-маршрута и запроса PerfHub, включая labels/series, временные параметры или права datasource. Для выбора исправления нужен фактический запрос; перебор маршрутов по одному 404 не обоснован.
- **D:** необычные кавычки в selector на фото. Аналогичный запрос уже есть в `DropAppFixtures.QUERY`, а сервис передаёт его буквально. Фотография не даёт основания исправлять запрос или объявлять его причиной 404.

## Дополнение: второй набор из пяти фотографий

**A:** редактор переменной явно показывает datasource `${datasource}`, тип `Label values` и целевую метку `deployment`. Включены Multi-value, Allow custom values и Include All, поле Custom all value пустое. Preview показывает 13 значений, включая All; это не доказательство наличия 13 конкретных deployment и не запись фактического HTTP-запроса. Эти настройки сами по себе не объясняют HTTP 404: в PerfHub проверка/фильтрация полученных вариантов выполняется после HTTP-чтения.

**A:** показанные фрагменты JSON панелей имеют структуру v2 (`elements`, `Panel`, `QueryGroup`, `PanelQuery`, `DataQuery`, `datasource.name`). Render-парсер PerfHub поддерживает элементы и RowsLayout/GridLayout, а также извлечение v2 datasource из переменных. На этих фото нет блока `variables`, layout целиком и ответа API, который получает PerfHub. Следовательно, подтвердить полную совместимость именно этого dashboard по ним нельзя; сам формат показанных панелей не устанавливает причину 404.

**D:** в поле Metric виден текст, начинающийся с `kube_deployment_status_replicas{namespace=~`, тогда как строка Label filters выглядит незаполненной. Это усиливает гипотезу о selector, сохранённом как имя метрики. [Grafana v13.0.1 variableMigration.ts](https://github.com/grafana/grafana/blob/v13.0.1/packages/grafana-prometheus/src/migrations/variableMigration.ts#L100-L117) строит label_values из отдельных metric и labelFilters. Для обычного имени метрики ожидается Metric `kube_deployment_status_replicas` и отдельный фильтр `namespace =~ $namespace`. Но полный текст поля и JSON переменной не видны; ни изменение dashboard, ни исправление кавычек в PerfHub из этого наблюдения не следуют. Даже подтверждённая ошибка selector не объясняет отсутствие этапа резервного запроса в прежнем отчёте.

Для следующего этапа достаточно точного блока `variables` с определениями datasource, namespace и deployment (или classic `templating.list`), включая `current` и `query`. Панели целиком для проверки UID/selector не требуются. Если передать файл нельзя, полезнее читаемый фрагмент этих переменных, чем фотографии всего JSON. До получения новых runtime-данных вывод о возможном несовпадении запущенной сборки и исходников сохраняется как гипотеза.

## Проверка в облачной среде

### Уточнение владельца о постоянном контексте

Владелец сообщил: источник данных всегда `prometheus`, namespace всегда `demo-namespace`. Это фиксирует ожидаемый пользовательский контекст, но не подтверждает фактические значения в сохранённых настройках PerfHub и не устанавливает UID datasource. По коду `datasource()` строка после подстановки используется непосредственно в URL с `/uid/`; поиска UID по имени нет. Если `prometheus` — только отображаемое имя, подстановка его вместо настоящего UID может приводить к 404 на обоих маршрутах. Для проверки достаточно UID из настроек этого источника Grafana; менять настройки или запрашивать токен не требуется.

Для стандартного selector целевой запрос в данном namespace имеет вид:

```text
label_values(kube_deployment_status_replicas{namespace=~"demo-namespace"}, deployment)
```

Это ожидаемая форма для сопоставления с фактическим запросом, а не восстановленный по фотографии JSON или разрешение жёстко прописать значения в приложении. Уточнение не объясняет расхождение этапов в прежнем отчёте; проверка запущенной сборки и свежего результата остаётся необходимой.

### UID из настроек источника

Следующие две фотографии настроек Grafana подтверждают: отображаемое имя источника — `prometheus`, UID в `/connections/datasources/edit/<uid>` — `current-source-uid`. Таким образом, имя и UID на этом стенде различаются. Пока не подтверждено, какое из них фактически сохранено в переменной datasource PerfHub.

Следующая адресная проверка: существующий read-only `GET /api/settings/grafana-dashboards` в пользовательской сессии PerfHub. Для dashboard dropapp сравнить `variables[].name == datasource` → `value`/`defaultValue` с подтверждённым UID, аналогично проверить namespace. `GrafanaVariableService` берёт непустое `value`, иначе `defaultValue`; render-каталог ставит эти значения выше dashboard.current.

Важные ограничения существующих настроек, проверенные по коду: страница `/settings/grafana-dashboards` не содержит редактора переменных, повторное «Запросить данные по дашбордам» сохраняет прежние непустые значения, а JSON-импорт общей страницы settings игнорирует grafanaDashboards. Поэтому эти операции не являются проверенным способом заменить устаревшее имя на UID. Backend `PUT /api/settings/grafana-dashboards/{uid}` поддерживает обновление variables, но требует сохранения slug, enabled и выбранных panels; его нельзя вызывать вслепую без чтения текущего dashboard. В рамках исследования никакие пользовательские настройки не изменялись.

### Пустой локальный список PerfHub

Следующая фотография подтверждает ответ `[]` от `GET /api/settings/grafana-dashboards`. Этот endpoint читает корневые записи `grafana_dashboards`; он не выполняет запрос обнаружения в Grafana. Render-каталог получает dashboard через собственные `/api/search` и `/api/dashboards/uid`, поэтому пустой локальный справочник не противоречит результату «8 обновлено / 1 отказ» и не объясняет HTTP 404.

Отсутствие overrides из `[]` также не следует: таблица `grafana_dashboard_variables` не имеет внешнего ключа на `grafana_dashboards`, а переменные читаются по dashboard UID независимо от корневой записи. В частности, метод удаления dashboard удаляет только корень. Это ограничение наблюдаемости, не установленное состояние пользовательской БД.

Штатное «Настройки → Графики и требования → Запросить данные по дашбордам» заполняет этот локальный список (и изменяет кеш настроек), после чего можно посмотреть объединённые переменные. Альтернатива без изменения локального справочника — прочитать в пользовательской сессии самой Grafana `/api/dashboards/uid/<uid-dashboard>` и взять `deployment`/`datasource`/`namespace` из `dashboard.variables` или `dashboard.templating.list`. В API UID dashboard берётся из сегмента `/d/<uid>/` адреса открытого DropApp; UID datasource `current-source-uid` и UID dashboard — разные сущности.

### Подтверждённое несовпадение сохранённого UID datasource

На последней фотографии API-ответа виден нужный `dashboard.templating.list`. Для переменной `name: datasource` сохранены `current.text: prometheus` и `current.value: stale-source-uid`. Значение читается с фотографии; это не полный машинно полученный JSON. Оно явно отличается от UID `current-source-uid`, подтверждённого отдельными фотографиями страницы настройки нужного источника.

**A:** API возвращает classic-структуру `dashboard.panels`/`templating.list`, несмотря на v2 в прежних снимках UI JSON. Текущий парсер берёт именно `current.value`. Сервис подставляет его в `${datasource}`, если нет более приоритетного локального override; клиент использует результат в обоих URL с `/uid/`. Поэтому переключение proxy/resources не исправляет устаревшую ссылку на источник.

**C:** сохранённый выбор datasource в DropApp не соответствует указанному владельцем источнику. Прежняя гипотеза «имя prometheus используется как UID» для самого dashboard.current не подтвердилась: там UID, но другой. **D:** наличие/доступность старого UID и фактический ответ после исправления пока не проверены. Несовпадение UID — доказанное наблюдение; устранение корпоративного 404 ещё не объявляется завершённым. Расхождение диагностического этапа старого отчёта с актуальным fallback остаётся отдельным наблюдением.

Наименьшая следующая коррекция — штатная настройка Grafana: открыть DropApp, заново выбрать datasource prometheus с UID `current-source-uid`, сохранить dashboard вместе с текущими значениями переменных как значениями по умолчанию. Затем заново прочитать JSON API и убедиться, что `templating.list[name=datasource].current.value` содержит именно этот UID; подпись `current.text` остаётся prometheus. После этого повторить проверку Grafana в PerfHub и сопоставить новый отчёт с ID проверки.

Это изменение конфигурации dashboard, а не изменение поведения приложения. Исправлять selector, добавлять новые маршруты или вводить resolver до результата этой проверки не требуется. Если JSON уже содержит правильный UID, но ошибка остаётся, проверяются свежий конечный этап/код, приоритет локальных overrides и доступ PerfHub к datasource; подтверждённое исправление не подменяется очередным предположением. Из облачной среды пользовательский dashboard и настройки не изменялись.

**Подтверждение владельца от 26.10.2026:** «Да, теперь заработало». Сценарий получения deployment после исправления сохранённого выбора источника подтверждён владельцем. Прямого корпоративного запроса из облачной среды не было; неизвестно, какой HTTP-маршрут завершился успешно. Следующий запрос об удалении служебного READY/диагностического вывода оформляется отдельным изменением `quiet-successful-grafana-checks` и не является продолжением исправления datasource.

26.10.2026 выполнены существующие тесты `GrafanaDeploymentClientTest`, `GrafanaRenderCatalogServiceTest`, `GrafanaCatalogDiagnosticsServiceTest`, `ConnectionTestGrafanaCatalogTest`: **46 выполнений, failures/errors/skipped = 0**, BUILD SUCCESS.

Среда: Oracle JDK 17.0.12, Maven 3.9.9. Перед запуском проверены разрешённые ключи конфигурации, стандартные тестовые настройки H2 и относительный путь `./config/perfhub-db`. Во временный каталог `/tmp/perfhub-deployment-investigation-d_5l30_w` скопированы только `pom.xml`, `src/`, `config/application.properties`. Пользовательские БД, кеши, экспорты и логи не копировались. HTTP подменён MockRestServiceServer; корпоративные адреса не вызывались.

Команда из временного корня:

```bash
mvn -B -ntp -Dtest=GrafanaDeploymentClientTest,GrafanaRenderCatalogServiceTest,GrafanaCatalogDiagnosticsServiceTest,ConnectionTestGrafanaCatalogTest test
```

Проверены, в частности, proxy 404 → resources success, конечный resources 404, сохранение окончательного этапа в безопасном отчёте, PARTIAL/STALE и отсутствие третьего запроса. Лог текущего прогона: `/workspace/perfhub-state/investigation-tests.log`; XML текущего запуска находятся в `target/surefire-reports` временного корня. Это доказательство локального контракта, а не успешного корпоративного подключения.

`openspec validate fallback-to-grafana-deployment-resources --strict` — успешно. Валидация проверяет артефакты, не соединение с Grafana.

## Следующая проверка на машине владельца

1. В фактически запускаемом проекте проверить `git rev-parse --short HEAD`, если есть Git metadata, и наличие перехода на `DEPLOYMENT_VALUES_RESOURCES` в `GrafanaClient.loadDeploymentLabelValues`. Наличие исходника отдельно не доказывает, что JVM загрузила именно его.
2. Остановить свой прежний процесс PerfHub. Из того же корня с JDK 17 выполнить `mvn -DskipTests clean package`, затем запустить `java -jar target/perfhub-0.0.1-SNAPSHOT.jar` из этого корня. Тесты в пользовательском checkout здесь намеренно не запускаются: `PerfHubApplicationTests` по умолчанию использует файловую H2. Если запуск идёт из IntelliJ, проверить выбранный проект и исключить работу старого процесса/другой копии.
3. Повторить «Проверить» для Grafana. Сопоставить ID проверки из уведомления с ID в заново записанном `logs/grafana-catalog-diagnostics-user-<id>.txt`; дату файла отдельно не считать достаточным подтверждением.
4. Если появляется конечная ошибка `DEPLOYMENT_VALUES_RESOURCES`, предоставить новый безопасный текстовый отчёт и обезличенный JSON dashboard, либо фрагменты переменных datasource/namespace/deployment с их `current`, `query`, `datasource`, `regex`. Для различения labels/series нужен метод и обезличенный путь запроса, который Grafana делает при обновлении deployment в браузере. Полный HAR, токены, cookies и заголовки авторизации не требуются.

После этих данных можно обосновать конкретное изменение транспорта или разрешения datasource и, если оно понадобится, подготовить отдельное OpenSpec-предложение для Review/Apply. Существующее согласование fallback не разрешает автоматически расширять последовательность запросов или менять selector.
