# Модель данных PerfHub

**A, анализ `schema.sql`, моделей и SQL репозиториев.** Пользовательская БД не открывалась и не изменялась. Состояние конкретного установленного экземпляра может отличаться от описанной схемы.

## Хранение и инициализация

H2 2.1.214, JDBC/JdbcTemplate. В локальной конфигурации задан относительный файловый datasource `./config/perfhub-db`; SQL initialization запускает `src/main/resources/schema.sql`. Файл совмещает `CREATE IF NOT EXISTS`, `ALTER`, преобразования и начальные справочники. Версионируемого механизма миграций Flyway/Liquibase нет. Имена/значения секретных seed-настроек не приводятся.

Помимо H2 есть каталог PNG `grafana-render-cache` с настраиваемым путём и кеши в памяти. HTTP-сессии также не представлены предметными таблицами. Архивы конфигураций собираются из сохранённых документов.

## Таблицы и отношения

| Таблица | Назначение и ключи | Связи / жизненный цикл |
|---|---|---|
| `app_users` | bigint ID, username, имя/фамилия, role, password_hash, даты | Логическое удаление `deleted_at`; индекс username **не UNIQUE**, активную уникальность проверяет сервис |
| `test_types` | integer ID; уникальный `test_type`, название, описание | Стандартные коды защищены сервисом; пользовательские удаляются физически |
| `tests` | UUID; start/end, тип, comment, report_status, report_summary | UNIQUE(start_time,end_time); FK типа с SET NULL; данные общие |
| `kubernetes_configurations` | Строки pod/container, release, CPU/RAM, время захвата | FK test_uuid CASCADE; прежнее представление конфигурации теста ещё используется |
| `dropapp_configuration_snapshots` | bigint ID; release, Jira key/url, comment, JSON namespaces/components, saved_at; `test_uuid` добавлен ALTER | Общий снимок либо связь с тестом; индекс test_uuid, **FK к tests в DDL не найден** |
| `dropapp_configuration_snapshot_items` | Нормализованные строки ресурсов | FK snapshot_id CASCADE |
| `dropapp_configuration_snapshot_documents` | namespace, component_code, resource_name, content CLOB | FK snapshot_id CASCADE; исходные документы для архива |
| `dropapp_configuration_snapshot_distributions` | service name/url, distribution name/url | FK snapshot_id CASCADE; сведения из Jira |
| `confluence_templates` | Старые именованные тексты шаблонов, уникальное name | FK типа CASCADE; используется hierarchy API, не тождественна новому каталогу |
| `confluence_report_templates` | short/full name, title/short title, type, page ID, body | Логическое удаление; активные имена проверяются сервисом; шаблоны TEST/RELEASE |
| `confluence_report_template_charts` | template_id, dashboard_uid, panel_id, title, sort_order | FK template CASCADE; FK панели нет |
| `confluence_release_report_links` | release page, report page, UUID теста, названия/URL, статус/выводы | Внешние page ID — строки; FK к tests и Confluence нет; локальные метаданные публикации |
| `confluence_release_report_blocks` | PK release_page_id, item_template, updated_at | Шаблон повторяемого блока ссылок; отдельная локальная запись |
| `grafana_dashboards` | PK UID, slug, JSON выбранных panels, enabled | Каталог настроек; сервис управляет связанными строками |
| `grafana_dashboard_panels` | PK(uid,panel_id), title, slug, query, threshold; дополнительный test_query через ALTER | Метаданные из Grafana и настроек; FK dashboard не найден |
| `grafana_dashboard_variables` | PK(uid,name), label/type/default/current value, options_json | FK dashboard не найден; значения для запросов/рендера |
| `grafana_rendered_panels` | ID; тест/шаблон/panel/период, variables_hash, размер/timezone, file_path, sha256 | FK test CASCADE; индекс поиска по параметрам. PNG отдельно; FK шаблона нет |
| `configurations` | ID, service/key, config_value, description, sort_order | UNIQUE(service,key); глобальные значения и описания параметров |
| `user_configurations` | ID, user_id, service/key/value, даты | UNIQUE(user,service,key), FK user CASCADE; персональные подключения |
| `gigachat_prompts` | ID, уникальный prompt_key, description/text, updated_at | Промпты из SQL; публичного CRUD UI не обнаружено |
| `test_requirement_evaluations` | ID, test_uuid, dashboard_uid/panel_id, status, measured_value, unit, details JSON, evaluated_at | FK test CASCADE; UNIQUE(test,dashboard,panel); перезаписываются при пересчёте |

Всего 21 таблица `CREATE TABLE` в текущем schema.sql. Наличие ограничений указано именно по DDL, а не подразумевается по названию поля. Дополнительные ALTER сохраняют совместимость исторических колонок; это не доказательство безопасного обновления любой старой БД.

## Жизненный цикл и целостность

Тесты возникают из обнаруженных VM-интервалов. `LoadTestRepository.saveOrUpdate` объединяет пересекающиеся/соседние интервалы с разрывом до 3 минут, оставляет один UUID и удаляет другие строки-кандидаты. Тесты подтверждают объединение через полночь и сохранение раздельных интервалов при большем разрыве. **C:** каскады могут затронуть связанные данные удаляемых UUID; полный сценарий сохранения комментариев/снимков при объединении не покрыт. Политика требует Q-09.

Ручное редактирование теста обновляет комментарий, статус/выводы и тип. Отдельного API удаления теста нет. Снимок теста при повторном захвате заменяет предыдущие снимки этого test_uuid; старые строки kubernetes также обновляются. Независимые снимки можно просматривать, менять release/comment, удалять вместе с дочерними записями. Удаление снимка не удаляет внешние ресурсы.

Публикация отчётов хранит итоговый документ в Confluence; H2 хранит шаблоны и release links/blocks, но не полную атомарную историю внешней операции. Сбой внешнего шага не откатывает созданные страницы. Файловый кеш и его таблица также не образуют единую транзакцию; чтение удаляет некоторые повреждённые записи, общей политики очистки устаревших PNG не обнаружено.

## Области настроек

`ConfigurationRepository` выделяет `victoria-metrics`, `grafana`, `dropapp`, `confluence`, `jira`, `gigachat` как персональные подключения. `ConfigurationService` определяет текущего пользователя и копирует ему отсутствующие глобальные строки подключений. Для других service значения общие; без пользовательского контекста читается глобальная конфигурация. `upsertAll` применяет записи последовательно, общей транзакции метода нет.

**C:** секреты сохраняются как значения конфигурации; прикладное шифрование этих полей не обнаружено. Экспорт в UI может включать их значения. Примеры реальных значений в документации запрещены. Разделение доступа к общим объектам и кешам — Q-04/Q-05, а не предположение о персональности всех данных.

## Расхождения и непроверенные свойства

- **C, подтверждённое расхождение:** `NonFunctionalRequirementRepository` выполняет CRUD таблицы `non_functional_requirements`, которой нет в schema.sql. Legacy API/страница существуют. В предыдущем аудите свежая изолированная H2 подтвердила отсутствие таблицы и SQL 42102-214. Рабочая пользовательская БД не проверялась. Текущая панельная оценка использует `test_requirement_evaluations`, а не эту таблицу.
- **C:** прикладная уникальность активных username/имён шаблонов не гарантирует защиту от конкурентных вставок на уровне БД.
- **D:** фактический объём данных, права файлов, резервное копирование, retention, восстановление после сбоя и совместимость пользовательской БД не исследовались. Они не объявляются работающими возможностями.

Решения и отдельные изменения — [реестр](requirements/gaps-and-decisions.md). Миграции, SQL, данные и исходники при подготовке baseline не изменялись.
