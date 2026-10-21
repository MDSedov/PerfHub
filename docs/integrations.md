# Внешние интеграции

Все шесть интеграций ниже — **A: подтверждены вызываемым кодом**, не проверкой корпоративной среды. Тесты используют подменённые ответы/клиенты. Реальных обращений в этом задании не выполнялось. Значения адресов, токенов, паролей и пользовательские экспорты не приведены. Названия параметров — ключи схемы настройки, не секреты.

Общий транспорт: `src/main/java/perfhub/config/RestTemplateConfig.java`, синхронный RestTemplate. Явные connect/read timeout не заданы в прикладном bean; фактические значения транспорта в среде не измерены. Общей политики retry нет. Настройки TLS `trust-store`, `trust-store-password`, `trust-store-type`, `disable-ssl-validation` читаются из группы `gigachat` при старте и применяются к общему клиенту (**C**, G-08). Изменение в UI не доказывает динамическую перестройку bean.

## VictoriaMetrics

- Назначение: временные ряды для поиска тестов, проверки панелей и анализа GigaChat.
- API: GET на `base-url` + `query-range-path`, query-параметры `query`, `start`, `end`, `step`; возвращается JSON временных рядов. Отдельного механизма авторизации в `VictoriaMetricsClient` не найдено.
- Настройки: `base-url`, `query-range-path`, `default-step-seconds`, `tests-search-query`. Пользовательские запросы используют текущий контекст, scheduler проверяет глобальные настройки.
- Поток: PerfHub → запрос измерений; VM → series/values. `VictoriaMetricsService` выбирает запрос intensity либо запрос выбранной панели Grafana, подставляет переменные, разбирает время `yyyy-MM-dd HH:mm:ss` в Europe/Moscow. Для batch диапазоны должны совпадать.
- Ошибки: отсутствующий запрос/неверный период — 400 через VM advice; клиентская/ошибка обработки ответа — 502. Автоматический повтор самого HTTP-запроса не найден; оценка требований перебирает варианты выражений при отсутствии данных, scheduler делает следующий периодический опрос.
- Доказательства: `client/VictoriaMetricsClient.java`, `service/VictoriaMetricsService.java`, `service/RequirementEvaluationService.java`, тесты обоих сервисов и клиента. Доступность конкретной VM — **D**.

## Grafana

- Назначение: каталог dashboard/panel, переменные, запросы и пороги требований, PNG графиков.
- API: GET `/api/search?type=dash-db`, `/api/dashboards/uid/{uid}`, `/api/health`; render GET `{render-path}/{uid}/{slug}` с `panelId`, `from/to` в миллисекундах, `width/height`, `tz`, `var-*`. Авторизация Bearer `api-key`.
- Настройки: `base-url`, `render-path`, `api-key`, `image-width`, `image-height`, `timezone`; выбранные панели/переменные хранятся отдельно в H2.
- Поток: PerfHub → фильтры и временной диапазон; Grafana → метаданные JSON и PNG. `GrafanaClient` проверяет PNG-сигнатуру, читает вложенные панели в rows, query и threshold. `GrafanaService` подставляет выбранные переменные, использует кеш и при устаревшем slug запрашивает актуальный и повторяет рендер.
- Ошибки: 400 при некорректном диапазоне/шаблоне, 502 для `GrafanaClientException`; правила HTTP-обработки cache miss зависят от контроллера. После сбоя получения metadata заголовок может браться из локального каталога. Общего retry не найдено.
- Пользовательские сценарии: «Графики и требования», детали теста, выгрузка графиков, отчёты Confluence. Публикация зависит от шаблона и готовых изображений.
- Доказательства: `GrafanaClientTest`, `GrafanaServiceTest`, `GrafanaDashboardSettingsServiceTest`. Реальный render plugin и права API — **D**. Замена ручных порогов при discover — A, решение Q-03.

## DropApp

- Назначение: чтение конфигурации стенда в формате Kubernetes API, снимки тестов и архивы.
- API: GET `/api/v1/namespaces`; для namespace — `/api/v1/namespaces/{namespace}/pods`, `/configmaps`, `/services`; deployment — `/apis/apps/v1/namespaces/{namespace}/deployments`; ingress — `/apis/networking.k8s.io/v1/namespaces/{namespace}/ingresses`.
- Авторизация: Bearer `token`; настройки `base-url`, `token`, `namespaces`. Состав компонентов/сервисов определяется общими настройками стенда или входом операции.
- Поток: PerfHub → выбор namespace; DropApp → JSON ресурсов, версии, labels, ограничения CPU/RAM и документы. Записи или удаления Kubernetes-ресурсов в клиенте не обнаружено.
- `DropAppService` фильтрует связанные ресурсы; snapshot service группирует pod, выбирает максимальный релиз, сохраняет строки и документы, затем привлекает Jira. При ошибке списка namespace разрешён fallback к непустому настроенному списку; при пустом fallback ошибка остаётся. Пустая конфигурация для захвата отклоняется.
- Ошибки: внешняя ошибка 502 в DropApp/LoadTest advice, входные/состояние 400, отсутствующий снимок 404. Общих timeout/retry поверх RestTemplate не найдено. Автозахват отдельных тестов логирует ошибки и продолжает.
- Доказательства: `DropAppClientTest`, `DropAppServiceTest`, `DropAppConfigurationSnapshotServiceTest`. Полнота реальных Kubernetes-ресурсов — **D**.

## Confluence

- Назначение: чтение шаблонов/дерева страниц, создание и обновление отчётов, загрузка PNG/ZIP.
- API относительно `api-path` (по умолчанию `/rest/api`): GET `content/{id}?expand=body.storage,version,space`; POST `content`; PUT `content/{id}`; GET `content/{id}/child/page` с пагинацией; `content/{id}/descendant/page` с fallback на `content/search`; POST multipart `content/{id}/child/attachment`; GET `space?limit=1` для проверки. Ссылки строятся как `/pages/viewpage.action?pageId=...`.
- Авторизация: настроенный `personal-access-token` передаётся в Bearer и `X-Confluence-Token`. Контроллер объявляет `X-Confluence-Login`/`X-Confluence-Token`, но token header фактически игнорирует, предпочитая конфигурацию (A; Q-07). Дополнительно для вложений — `X-Atlassian-Token: no-check`.
- Настройки: `base-url`, `api-path`, `personal-access-token`, `reports-parent-page-id`. Тела шаблонов, page ID и графики — отдельные сущности H2. Зависимости: тесты, Grafana, снимки стенда/ZIP, локальные блоки ссылок, Jira macro и настройки проекта.
- Поток: JSON/storage XHTML и бинарные вложения в обе стороны. `ConfluenceService` заменяет placeholders, создаёт страницы, загружает вложения, обновляет body/version; формирует отдельную страницу релиза и блок ссылок. Публикация в несколько страниц не является внешней транзакцией.
- Повторы: ожидание доступности страницы до 6 попыток с паузой 700 мс; отдельные повторы upload/update до 5 попыток с паузой 700 мс; конфликт заголовка ведёт к новому варианту заголовка. Для дерева страниц действует ConfluenceRequestThrottler с интервалом по умолчанию 1000 мс (`confluence.report-parent-pages.request-interval-ms`); это не гарантия отсутствия rate limit. Ошибки: 400 операция/шаблон/диапазон, 409 недостаточный кеш, 502 клиент. Сбой после создания страницы может оставить частичный результат (C).
- Доказательства: 15 `ConfluenceServiceTest` с mocks, включая placeholders, уникальный title, tree, release links и append. Прямого теста маскирования логов нет. **C:** INFO содержит карту auth headers (G-01); в нормативные требования это не включено.

## Jira

- Назначение: найти релизную задачу и дистрибутивы для конфигурации стенда; ссылки/макросы в отчётах. Записи в Jira не обнаружены.
- API: GET `{api-path}/myself`, `/project/{project}/versions`, `/search` с JQL, `fields`, `expand=renderedFields`, `maxResults=50`; default `api-path=/rest/api/2`.
- Авторизация Bearer `personal-access-token`; настройки `base-url`, `api-path`, `personal-access-token`, `project-name`. Для поиска релиза нужны base URL, token и project.
- Поток: название релиза/проект → Jira; версии, description, issue keys, HTML/wiki таблицы дистрибутивов → PerfHub. Сначала сопоставляются версии, затем используется поиск; HTML и wiki разбираются клиентом. Snapshot service может сохранить конфигурацию без найденной Jira-связи, сообщения о недоступности логируются. HTTP retry и явные timeout не найдены.
- Доказательства: 8 `JiraClientTest` и snapshot-тест на сохранение ссылки. Содержимое реальных описаний Jira и полнота поиска — **D**.

## GigaChat

- Назначение: текстовый анализ метрик по сохранённым промптам; дополнительный API анализа соответствия. Это отдельный путь от числовой оценки `RequirementEvaluationService`.
- API: POST настроенного `oauth-url`, form `scope`, `client_id`, `grant_type`; `Authorization: Basic` с `authorization-key`, `RqUID`. POST настроенного `api-url`, Bearer access token, JSON `model` и `messages`, `RqUID`.
- Настройки: `oauth-url`, `api-url`, `scope`, `authorization-key`, `client-id`, `grant-type`, `model`, перечисленные выше TLS-параметры.
- Поток: промпт/подставленные variables либо последний агрегат VM → GigaChat; JSON completion → вызывающий код. `/tests/` использует system+user messages и пишет response cache; `/test/compliance/` использует отдельный промпт. Тело `/tests/` принимается, но не используется (Q-06).
- Ошибки: отсутствующие prompt/данные VM — 400, ошибка загрузки prompt — 500, клиент — 502. Токен переиспользуется до истечения с запасом 30 секунд; общего retry нет. `GET /api/gigachat/token` возвращает токен клиенту — A, но целесообразность требует решения и не закреплена как требование.
- Доказательства: `GigachatServiceTest.analyzeLoadTestsSendsSystemAndUserMessages`; реальная модель, её качество и подключение — **D**.

## Другие внешние зависимости

jsDelivr отдаёт Bootstrap CSS/JS и Bootstrap Icons браузеру. Это зависимость отображения от CDN, не серверная бизнес-интеграция. Картинки/логотипы PerfHub локальные. Других серверных HTTP-клиентов при инвентаризации не найдено; это не аудит сетевого трафика работающего приложения.
