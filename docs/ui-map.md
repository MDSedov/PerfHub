# Карта интерфейса PerfHub

**A — анализ всех 18 HTML-файлов, их inline-логики и трёх внешних JS.** Страницы не открывались на работающем приложении: визуальная совместимость, доступность и сквозные действия не проверены браузером. Дизайн и ресурсы не менялись. Полные контракты маршрутов — [API](api-and-routes.md).

## Навигация и роли

`fragments/sidebar.html` содержит логотип → `/main`, «Тесты и отчеты» → `/tests`, «Выгрузить графики» → `/grafana-panels`, «Сохранить конфигурацию» → `/configuration`, «Настройки» → `/settings` и подразделы connections, stand-configuration, templates, grafana-dashboards. В footer ссылка с именем пользователя ведёт на `/profile`.

Только `ADMIN` видит административный раздел с users/test-types. Сервер дополнительно защищает `/admin/**` и `/api/admin/**`; скрытая кнопка не является единственной проверкой. Все остальные перечисленные настройки доступны `USER` и `ADMIN`. Login публичный. `/reports`, `/settings/requirements`, `/settings/victoria-metrics` имеют HTML-handler, но отсутствуют в актуальном общем меню. Для `index.html` явный handler не найден. Наличие старой страницы не доказывает активный пользовательский сценарий.

## Страницы и элементы

Все шаблоны находятся в `src/main/resources/templates/`. Имена modal/form ниже — фактические DOM ID для будущих проверок совместимости, не предложение новой разметки.

| Шаблон / маршрут | Формы, таблицы и действия | Модальные окна / уведомления | Связанные API |
|---|---|---|---|
| `login.html` — `/login` | Логин, пароль, скрытый redirect; отправка формы | Серверное сообщение неверного входа; без модального окна | POST /login |
| `main.html` — `/main` | Карточки переходов к основным операциям | Общие фоновые уведомления; бизнес-форм нет | Нет собственного бизнес-API |
| `tests.html` — `/tests` | Таблица тестов, выбор строк; запрос за 30 дней; фильтры релиз/тип/длительность/период/оценка/конфигурация; сброс. В деталях тип, комментарий, статус/выводы, графики, результаты проверки и ресурсы стенда | `testDetailsModal`, `generateReportsModal`; `testsStatus`, `toastContainer`, индикаторы ожидания по тестам | GET /api/tests, /api/test-types; POST /api/victoriametrics/tests/; POST /api/tests/{id}/comment, /report-details, /type, /configuration/capture; GET /configuration и /configuration/archive; POST /api/requirements/status, /evaluate; POST /api/grafana/panels/; Confluence report/tree/release/links |
| `reports.html` — `/reports` | `reportForm`: родительская страница, выбор тестов в таблице, публикация | `reportAlert`, состояние таблицы; отдельного modal нет | GET /api/tests, /api/perfhub/properties/; POST /api/confluence/report/ |
| `grafana-panels.html` — `/grafana-panels` | Выбор периода и шаблона, показ панелей; указание страницы для добавления изображений | `showPanelsModal`, `exportPanelsModal`, toast | GET /api/settings/confluence-templates; POST /api/grafana/panels/range/panel; POST /api/confluence/charts/append/ |
| `configuration.html` — `/configuration` | Таблица сохранённых снимков, захват выбранных компонентов/namespace/сервисов; детали ресурсов/дистрибутивов, редактирование release/comment, архив, удаление | `dropAppCaptureModal`, `dropAppConfigurationModal`, toast | GET /api/dropapp/configuration/saved, /namespaces, /services; GET /api/settings/stand-configuration; POST /api/dropapp/configuration/capture; GET/PATCH/DELETE /saved/{id}; GET /saved/{id}/archive |
| `settings.html` — `/settings` | Выгрузка выбранных разделов, файловый input импорта JSON; последовательные операции и итоговые счётчики | `exportSettingsModal`; обычные и длительные уведомления прогресса | properties GET/PATCH; stand-configuration GET/PATCH; confluence-templates/export GET; confluence-templates POST/PUT с validateCharts=false; test-types GET; connections/{service}/test POST |
| `settings-connections.html` — `/settings/connections` | Таблица подключений, выбор сервиса, динамические поля `connectionForm`/`connectionDetailsForm`, сохранение/проверка | `connectionSelectModal`, `connectionFormModal`, `connectionDetailsModal`, `connectionFormMessage`, toast | GET/PATCH /api/perfhub/properties/; POST /api/perfhub/connections/{service}/test |
| `settings-stand-configuration.html` — `/settings/stand-configuration` | Выбор namespace, компонентов и сервисов, сохранение состава стенда | Статусы загрузки/сохранения и toast; отдельного modal нет | GET/PATCH /api/settings/stand-configuration; GET /api/dropapp/services |
| `settings-templates.html` — `/settings/templates` | Таблица шаблонов; тип теста/релиза, заголовки, page ID/ссылка, HTML-редактор, таблица выбранных графиков с порядком; сохранение/удаление | `templateDetailsModal`, toast | GET /api/test-types; GET/POST /api/settings/confluence-templates; GET /panel-options; PUT/DELETE /{id} |
| `settings-grafana-dashboards.html` — `/settings/grafana-dashboards` | Плоская таблица dashboard/график/номер/query/порог; фильтр dashboard, поиск по названию, сброс, обновление из Grafana | `dashboardsStatus`, tooltips, toast; отдельного modal нет | GET /api/settings/grafana-dashboards и /discover. Наличие POST/PUT API не означает, что таблица даёт редактор всех DTO-полей |
| `settings-requirements.html` — `/settings/requirements` | Legacy список/редактор кода НФТ, metricType, порога/единицы, active, описания и parameters JSON | Встроенные статусы, без Bootstrap modal | GET/POST /api/settings/non-functional-requirements; PUT/DELETE /{id}; **C:** таблицы SQL нет |
| `settings-victoria-metrics.html` — `/settings/victoria-metrics` | Legacy список/редактор запросов VM | Встроенные статусы, без Bootstrap modal | Вызывает /api/settings/victoria-metrics-queries[/{id}]; **C:** handler отсутствует |
| `admin-users.html` — `/admin`, `/admin/users` | Таблица активных пользователей; `userDetailsForm`: логин, имя/фамилия, роль, пароль/подтверждение; создание/изменение/удаление | `userDetailsModal`, toast | GET/POST /api/admin/users; GET /roles; PUT/DELETE /{id} |
| `admin-test-types.html` — `/admin/test-types` | Таблица типов; `testTypeDetailsForm`: name/code; создание, редактирование, удаление с ограничениями standard | `testTypeDetailsModal`, toast | GET/POST /api/admin/test-types; PUT/DELETE /{id} |
| `profile.html` — `/profile` | `profileForm`: имя/фамилия, логин, новый пароль/подтверждение; отображение роли; выход | Toast, без modal | GET/PUT /api/profile; POST /logout |
| `index.html` — явного маршрута нет | Прежняя landing-разметка и сценарий lucky reports; подключает `lucky-reports.js` | `luckyReportsModal`, `luckyReportsModalForm`, toast | Скрипт обращается к API тестов, VM, Grafana и Confluence; достижимость страницы **D** |
| `fragments/sidebar.html` — фрагмент | Меню, активные пункты и профиль по роли | Переключение collapsed через общий JS | Навигация HTML, собственного бизнес-API нет |

## Взаимосвязанные пользовательские действия

На `/tests` загрузка списка и фильтрация — разные шаги: API возвращает весь сохранённый список, отбор происходит в браузере. «Запросить данные за 30 дней» инициирует поиск VM с записью найденных интервалов; проверка требований может также обращаться к VM и сохранять оценку. Детали теста объединяют несколько независимых API, поэтому успешная загрузка списка не доказывает готовность графиков или снимка.

Форма создания отчётов получает шаблоны и дерево Confluence, связывает выбранные тесты с шаблонами, подготавливает изображения, создаёт релизную страницу/отчёты и обновляет ссылки. Это последовательность HTTP-запросов, а не атомарное действие с полным откатом. Частичный результат — G-11.

Импорт настроек: JSON читается браузером; подключения, стенд и шаблоны применяются отдельными запросами. После импорта подключений выполняются проверки внешних сервисов. Старые разделы grafanaDashboards/nonFunctionalRequirements пропускаются с уведомлением. Экспорт раздела подключений может включать секретные значения; такой экспорт в задании не выполнялся и нормативно не утверждён (Q-05).

## Общие визуальные элементы и состояние

Логотип PerfHub, зелёные основные действия/акценты, светлое рабочее поле, sidebar, карточки, Bootstrap-таблицы, формы, badges, tooltips, alerts и toast составляют текущий стиль. Имеются повторяющиеся реализации уведомлений и fallback modal. CSS допускает разные итоговые размеры toast из-за каскада. Это наблюдение по стилям, не измерение в браузере.

`sidebar-collapse.js` и ранние inline-скрипты читают `perfhub.sidebar.collapsed`. В последнем каскаде CSS sidebar имеет ширину 340 px на desktop, 88 px collapsed; граница mobile/desktop — 992/993 px. Версии URL app.css различаются между шаблонами. `background-errors.js` опрашивает очередь; tests дополнительно работает с областью load-tests. Очередь потребляется и не разделена по пользователям.

**C:** нет автоматического доказательства корректного Thymeleaf-рендеринга, состояний modal, управления клавиатурой, узких экранов или всех DOM ID. Перечень необходимых будущих проверок уже находится в [аудите](audit/codebase-audit.md) и неизменённом refactor-shared-ui-layout (`openspec/changes/refactor-shared-ui-layout/proposal.md`, локальный неутверждённый план). Общая структура не переделывалась.
