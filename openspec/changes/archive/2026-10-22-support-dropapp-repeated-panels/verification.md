# Verify support-dropapp-repeated-panels — обновлено 22.10.2026

Review и отдельный запрос Apply получены от владельца. **Apply и Verify завершены 22.10.2026: 23/23 задач.** Ручной drag/save/reopen подтверждён владельцем; оставшийся браузерный прогон завершён разрешённым им Playwright на изолированном стенде. Матрица ниже различает автоматические тесты, настоящий renderer, браузер и подменённые ответы отрицательных сценариев. Корпоративные подключения и пользовательская БД не проверялись. По отдельной команде владельца 22.10.2026 выполнен штатный Archive с обновлением основных specs; commit/push разрешены последующим Git-шагом. Merge/deployment не выполнялись. Текущее состояние — в [tasks](tasks.md).

## A — реализация и воспроизводимые проверки

- Строка подходящей панели DropApp хранит один конкретный deployment; все его pod/container запрашиваются одним PNG. Базовый числовой panelId сохранён. Транспорт, UID/slug route, bearer и fallback обычного рендера переиспользованы.
- Отдельный render-каталог обновляется после успешного health; редактор читает snapshot. Каталог не вызывает discover НФТ, не записывает query/threshold/testQuery и не запускает пересчёт. Health и состояние каталога разделены.
- Строки различаются по templateChartId; PNG-кеш учитывает deployment, источник/revision, datasource/namespace, fingerprint, reference/All, период и размеры. Обычный ключ сохранён. Экспорт не переносит служебные id.
- Название дополняется `[deployment: значение]`, порядок восстанавливается между dashboard. Подготовка PNG предшествует записям create/append; attachments отдельные, размеры разметки берутся из общих настроек.
- [Описание реализации, API, ограничений и отката](../../../../docs/dropapp-rendering.md). Подтверждение кодом не объявляется доказательством корпоративного подключения.

## Безопасная среда и автоматические проверки

Создана временная копия только `pom.xml` и `src/`. Вместо копирования пользовательской конфигурации сформирован файл с новой относительной H2 `./config/isolated-test-db`, отдельным `isolated-render-cache`, пустыми подключениями и задержкой планировщика 86400000 мс. Проверены разрешение пути внутри временного корня и отсутствие прежних файлов БД до первого запуска. Репозиторные тесты используют уникальные embedded H2, файловый кеш — JUnit @TempDir. Пользовательские БД, кеши, секретные экспорты не копировались.

HTTP клиентов в Maven подменён MockRestServiceServer/Mockito. Единственный SpringBootTest запускает свежую схему с пустыми интеграциями и отложенным scheduler. Минимальное окружение процесса исключает унаследованные Spring/JVM-параметры; Maven offline, пользовательский и глобальный settings пустые. Команда выполнялась **из временной копии**, не из рабочего проекта:

```text
mvn -o -B -ntp -s empty-settings.xml -gs empty-settings.xml test
```

| Проверка | Результат / граница |
|---|---|
| Исходный прогон до Apply | 130 тестов, BUILD SUCCESS; 0 failures/errors/skipped |
| Итоговый полный прогон | Corretto **JDK 17.0.11**, Maven 3.9.10; **163 теста**, 32 класса, 0 failures/errors/skipped |
| Чистая и прежняя синтетическая схема | nullable-колонки, прежние id/title/null, повторный запуск DDL сохранены; snapshot и настоящие PNG-файлы читаются из новых H2/временного каталога |
| JS без новых зависимостей | `node src/test/js/dropapp-contracts.cjs` — PASS; экспорт/импорт, перенос deployment при перестановке и actual saveTemplate payload, порядок между dashboard, локальная ошибка/продолжение, 30 с, чужой/поздний ответ, повторный cacheOnly |
| Inline JS | `node --check` извлечённых скриптов затронутых страниц; синтаксических ошибок нет |
| Контракты | Старые GrafanaClientTest/GrafanaServiceTest/ConfluenceTemplateSettingsServiceTest и остальные исходные тесты сохранены. MVC проверяет 200/400/502 и message; отдельный локальный запуск проверяет роли |

Временные Surefire XML и `dropapp-final-tests.log` находятся в `/var/folders/24/wchh7vqs2r15pcnmhbpwymvm0000gn/T/perfhub-dropapp-baseline-_afmpx8t/`. Это диагностические временные файлы, не постоянные артефакты проекта. Промежуточные ошибки в добавленных тестах (лишняя скобка и повторная настройка throwing mock) исправлены; проверки не ослаблялись.

## Изолированная Grafana и фактические PNG

Использованы официальные образы **Grafana 13.0.1**, **grafana-image-renderer:v5.12.8** и Python 3.12 для синтетического Prometheus HTTP. Grafana/renderer/источник соединены внутренней Docker-сетью; доступ стенда опубликован только на loopback через тестовый proxy. Отдельный PerfHub запущен из временной копии на новой `isolated-ui-db`, с синтетическими пользователями и отдельным кешем. Корпоративные URL/БД не используются. Версия корпоративного renderer по этим данным не устанавливается.

Тестовый dashboard — Classic API envelope с row repeat deployment, панелями CPU 106/RAM 78 и зависимостями namespace → deployment → pod → container. Fixtures обезличены и **не являются восстановленным полным JSON корпоративного dashboard**. Точный необычный selector из подтверждённого определения передан буквально; источник стенда возвращает контролируемые серии и не доказывает семантику корпоративного Prometheus selector.

Проверен полный путь PerfHub: успешный health → загрузка каталога → сохранение трёх строк → одиночный range → реальный Grafana renderer → PNG. Получены:

| Строка | Фактический reference / результат |
|---|---|
| CPU/profile | `profile$panel-106`, 1000×1000, 3 pod × 3 container × usage/request/limit = 27 строк легенды |
| CPU/other | `other$panel-106`, 1000×1000, только 2 pod other × 3 container × 3 серии = 18 строк; pod profile отсутствуют |
| RAM/profile | `profile$panel-78`, 1000×1000, все 27 строк usage/request/limit; квадратные пропорции |
| Другой размер | Общая высота в синтетической БД временно 500: PerfHub получил реальный PNG 1000×500, затем настройка стенда возвращена к 1000 |
| Отсутствие метрик | Источник вернул пустые range-серии: получен реальный PNG 1000×1000 с «No data» и контекстом CPU/profile; подстановки изображения/серий нет |
| Ошибка каталога | Синтетический datasource HTTP 503: health `success=true`, catalog STALE, прежние два deployment сохранены; повторная проверка после восстановления — READY |

PNG `perfhub-row-1.png`, `perfhub-row-2.png`, `perfhub-row-3.png`, `perfhub-no-data.png`, `perfhub-alternate-size.png` визуально/по IHDR проверены в `/var/folders/24/wchh7vqs2r15pcnmhbpwymvm0000gn/T/perfhub-dropapp-renderer-2w2kfgdg/`. Полная легенда 27/18 серий помещается на **этом синтетическом примере**; более длинные имена и произвольное число серий не проверены. Исходные метрики/оформление корпоративной панели не заменялись.

## Приложение и браузер

Thymeleaf фактически отрендерил `/settings`, `/settings/templates`, `/settings/connections`, `/grafana-panels`, `/tests`: HTTP 200. Проверка шаблонов и panel-options не увеличила число запросов синтетического datasource. Гость получил API 401, USER — 403 для административного API; другой пользователь получил пустой собственный каталог, без списка ADMIN. Это проверка локального приложения, не корпоративных ролей.

В Chrome проверены modal шаблона, три независимых deployment-select, отсутствие All/pod выбора, смена deployment одной строки, save/toast/reopen, отображение STALE, активная навигация и collapse/expand sidebar. Viewport 992/993 px проверен через DevTools: при 992 действует прежняя мобильная раскладка, при 993 — боковое меню и основная область; новые поля помещаются с переносом подсказки. Отдельное окно инкогнито подтвердило health «Активно» одновременно с catalog STALE и предупреждением после «Сохранить и проверить». Длинное сообщение вынесено в toast/title, в ячейке остаются status/time с переносом.

Настоящий API export → действующие JS-нормализаторы settings → offline import → JDBC read сохранил CPU/profile, CPU/other, RAM/profile и порядок; id назначены заново. Это проверка формата и API, не имитация нажатия файлового диалога.

В продолжении браузерной проверки под `dropapp-qa`: смена CPU → RAM очистила выбор deployment и обновила название, save без выбора показал понятную ошибку; после закрытия без сохранения исходная строка восстановлена. При успешном EMPTY сохранённые profile/other не заменены, показано отсутствие в каталоге; исправлена подсказка, которая прежде ошибочно называла успешный пустой список недоступным. Повторное сохранение существующих значений дало зелёное «Шаблон сохранен» и новые id в БД. Затем стенд возвращён в READY, все экспериментальные флаги ошибок/пустых данных сняты, CPU/profile снова дал реальный PNG 1000×1000. Maven после этой правки: 163/163, без ошибок/пропусков.

В первоначальном прогоне CUA при открытии формы периода запросил доступ к clipboard; запрос отклонён, пользовательский буфер не читался. Затем AX и фактическое окно перестали согласовываться (`noWindowsAvailable`). Это ограничение инструмента снято для Verify отдельным разрешением владельца использовать Playwright; результаты завершённого прогона приведены ниже. Разрешения браузера и Bootstrap не менялись.

Для range/test UI действующие функции страницы проверены на синтетическом транспорте: ошибка первой строки не мешает двум следующим; чужой id отклонён; поздний ответ прежнего запуска не применён; повторное открытие выполняет новый локальный cacheOnly вместо старого HTML/testId. После отдельного разрешения владельца эти ветки дополнительно проверены в настоящем браузере, как описано ниже.

## Браузерное завершение Verify — 22.10.2026

Владелец отдельно разрешил Playwright. **Playwright 1.56.1 установлен только во временный каталог**, вне проекта; использован установленный Chrome **155.0.8059.85** в отдельном headless-профиле с включённой sandbox. Зависимости PerfHub, browser security/SRI и production-конфигурация не менялись. Допущены loopback PerfHub и публичные статические CDN; неожиданных внешних запросов и ошибок выполнения JavaScript нет. Браузерные HTTP-запросы/DOM и PNG проверены непосредственно, не через VM-функции.

После остановки Docker недостающие три контейнера стенда восстановлены из тех же официальных образов и сохранённых обезличенных fixtures. Тестовый service-account token пересоздан только для новой локальной Grafana; проверка PerfHub вернула health=true/catalog=READY. Начальный отказ соединения относился к остановленному стенду, а не к дефекту DropApp. Небезопасные корпоративные обращения не выполнялись.

| Проверка | Фактический результат / уровень доказательства |
|---|---|
| Период из формы | Настоящие UI → API → Grafana/renderer: CPU/profile, RAM/profile, CPU/other в сохранённом владельцем порядке; три отдельных PNG 1000×1000. DOM подтверждает отображение 922×922 без искажения; названия и row id соответствуют строкам. Скриншот проверен визуально: CPU/RAM содержат usage/request/limit и полную легенду синтетического примера. |
| Частичная ошибка | Настоящий тестовый proxy вернул 503 только для CPU/profile: ошибка показана в нужной строке, RAM/profile и CPU/other загрузились. После снятия флага следующий запуск получил три изображения. |
| Графики теста | Реальная форма деталей теста получила три PNG в порядке шаблона. Повторное открытие выполнило новый локальный cacheOnly; счётчик proxy подтвердил отсутствие запросов Grafana. Квадратные PNG отображаются 1205×1205 в широком modal, пропорции сохранены. |
| Обновление шаблона без reload страницы тестов | В настоящем редакторе изменён deployment, выполнен HTML5 drag-and-drop и успешный save. Уже открытая страница тестов после закрытия/reopen modal получила свежие templateChartId, названия/deployment и порядок из серверного кеша, без Grafana HTTP. Прежний HTML не вернулся. Исходные значения/порядок шаблона затем восстановлены и сверены через API; переиздание id — прежняя семантика repository. |
| Ошибочная строка, timeout и поздний ответ | Отдельный браузерный прогон с **перехватом только render API**: чужой templateChartId отклонён, остальные две строки продолжены. Настоящий AbortController сработал через 30096 мс, показал ошибку 30 секунд и продолжил строки. После нового успешного запуска поздний ответ отменённого запроса не изменил DOM. Эти отрицательные ответы подменены на стенде; они не объявляются реальным timeout корпоративного renderer. |
| Редактор/навигация 992/993 px | Три select имеют связанные label, без All/pod; поля находятся в ширине modal. При 992 px сохранена мобильная раскладка с **скрытой** кнопкой collapse по действующему CSS; при 993 кнопка видна, collapse/expand работает. Активный пункт шаблонов сохранён; скриншоты проверены. |
| Роли | Гость: 401 API шаблонов. USER: 200 чтение шаблонов, 403 `/api/admin/users`, административных ссылок в меню нет. ADMIN использует редактор. Это существующее разграничение, настройки не объявляются целиком административным API. |

Три скрипта завершились **17 успешными контрольными точками**. Временные скрипты: `/tmp/perfhub-dropapp-browser-smoke.cjs`, `/tmp/perfhub-dropapp-browser-negative.cjs`, `/tmp/perfhub-dropapp-browser-final-ui.cjs`. JSON-результаты, безопасные запросы API и скриншоты находятся в `/var/folders/24/wchh7vqs2r15pcnmhbpwymvm0000gn/T/perfhub-dropapp-browser-ktdm4wu9/`; это временные диагностические артефакты. Проверка вывода исходников подтвердила их полное совпадение с копией последнего Maven 163/163; повторный Maven для изменения только журнала не запускался.

В форме синтетического теста видна обработанная ошибка НФТ об отсутствии графиков в прежнем каталоге: этот стенд наполняет отдельный render-каталог, не старые query/threshold НФТ. Это ожидаемое ограничение fixtures, не изменение правил НФТ. Временный harness первоначально неверно ожидал кнопку collapse при 992 и запрет USER читать шаблоны; проверки исправлены по неизменённым CSS/AuthenticationInterceptor и затем прошли, продуктовый код не менялся.

## C/D — ограничения и история проверки

1. **Drag-and-drop/save/reopen подтверждён; блокирующей проверки больше нет.** Владелец подтвердил сохранение порядка после зелёного сообщения. Только синтетический API показал новые id 19/20/21 и CPU/profile, RAM/profile, CPU/other. Первоначальный отрицательный результат относился к неуспешному save под пользователем без своего каталога (400 без изменения БД); потеря порядка после успешного сохранения не подтверждена. Playwright дополнительно проверил настоящий drag/save и обновление уже открытой страницы тестов. Ошибки инструмента CUA сохранены как история, задачи 4.3/7.1 завершены без сокращения требований.
2. **Известная прежняя ошибка Bootstrap JS SRI.** Браузер блокирует bundle 5.3.3 из-за несоответствия integrity. Строка подключения совпадает с HEAD; изменение версии/хеша вне объёма. Fallback modal/toast использован и проверен, но это не проверка штатного Bootstrap modal. В обычном профиле также наблюдалась ошибка расширения `Extension context invalidated`; расширения/защита браузера не менялись.
3. **Корпоративное подключение не проверялось агентом.** Фото подтверждают форму API/переменных и один успешный scoped PNG; владелец подтвердил работающий ordinary рендер. Datasource-права, буквальный selector на настоящем источнике, корпоративный renderer, история удалённых pod и фактическая длина легенды остаются эксплуатационными ограничениями. Поиск исторических pod отдельными метриками не добавлялся.
4. **Confluence проверен с mock-транспортом.** Create/append: три отдельных attachments, escaped titles, порядок, width/height=1000, неизменные байты PNG, подготовка до любой записи. Реальная корпоративная страница/масштабирование Confluence не вызывались и не заявляются проверенными.

## Матрица всех 33 сценариев

Обозначения: **T** — автоматический тест, **L** — локальное приложение/реальная Grafana на синтетике, **U** — ручная либо автоматическая проверка настоящего браузера, **D** — явно оставшееся ограничение. T не означает корпоративную проверку. Ссылки на тесты — ниже таблицы.

| Сценарий | Доказательство / фактический результат |
|---|---|
| PH-GRAF-006-S01 | T parser Classic flat repeat и RowsLayout; сохранены base refs, row/копии исключены; L Classic API 13.0.1 |
| PH-GRAF-006-S02 | T parser и исходный GrafanaClientTest: nested rows/ordinary id; T panelOptionsKeepOrdinaryLookupAndAddOnlyScopedSnapshotPanels |
| PH-GRAF-006-S03 | T rejectsUnknownEnvelopeRatherThanPublishingEmptyCatalog + failureRetainsSnapshotAsStaleAndEmptySuccessReplacesOptions: ошибка без очистки |
| PH-GRAF-006-S04 | T rowsLayoutUsesElementsAndDoesNotScopeUnfilteredPanel: нефильтруемая панель ordinary |
| PH-GRAF-007-S01 | T onePngPerRowUsesScopedReferenceAndAllWithoutMutatingGlobals; L CPU/profile 27 usage/request/limit серий |
| PH-GRAF-007-S02 | T три независимых строки; L CPU/profile и CPU/other имеют разные pod и по одному PNG |
| PH-GRAF-007-S03 | T overrides pod/container, исходная карта не изменена; L renderer разрешил $__all для конкретного deployment |
| PH-GRAF-007-S04 | T local catalog без discover; code diff: вызовов/записей НФТ/VM нет; исходные NFR-тесты проходят |
| PH-GRAF-007-S05 | T scopedReferenceUsesExistingRouteAuthAndConfiguredSize; L фактические 1000×1000 и 1000×500 из общих настроек |
| PH-GRAF-008-S01 | T realFileAndH2CacheRespectDeploymentSizeSourceAndCacheOnly: другой deployment не читает PNG profile |
| PH-GRAF-008-S02 | T rowIdDisambiguatesAndForeignOrLegacyAmbiguousRequestFails + MVC: нужная строка, чужой/stale id и mismatch отклонены |
| PH-GRAF-008-S03 | T тот же тест: ambiguous 400/message, уникальный legacy range работает |
| PH-GRAF-008-S04 | T настоящий H2/файл: cacheOnly без render, forceRefresh повторяет; U повторное открытие modal после изменения шаблона проверяет серверный кеш без Grafana HTTP |
| PH-GRAF-008-S05 | T owner/revision/datasource/namespace/late revision, новый source context cache miss; T changingSourceDuringRequestDoesNotReturnOrCacheImage |
| PH-GRAF-008-S06 | T переход 1000×500 → 1000×1000: cacheOnly miss без HTTP |
| PH-GRAF-009-S01 | T клиент non-PNG/empty; service fail-fast/no-cache; MVC 502; U настоящий 503 одной строки продолжает остальные; U перехват API проверил id, 30 с и поздний ответ |
| PH-GRAF-009-S02 | L реальный PNG «No data», один нужный контекст, 1000×1000 |
| PH-GRAF-009-S03 | T исходные client/service tests + ordinary options/import/JSON контракты; D корпоративный повторный ordinary smoke |
| PH-REP-008-S01 | T missingAllListsAndUnknownScopeRejectedEvenForOfflineImport, JSON array reject; U конкретный select без All/pod; смена CPU → RAM очистила deployment, save без нового выбора показал ошибку |
| PH-REP-008-S02 | T JDBC три строки/id/order + JS moveTemplateChart; U владелец подтвердил нативный drag/зелёный save/reopen; U Playwright drag/save и повторное открытие теста отразили новый порядок/deployment/id; L PUT/JDBC/GET |
| PH-REP-008-S03 | T disappearedExistingDeploymentCanBeRetainedButNotAddedAsNew; U STALE hint; U при EMPTY показаны отсутствующие сохранённые значения, save завершился зелёным toast, прежние deployment сохранены |
| PH-REP-008-S04 | T recognized legacy без deployment отклонён при save/render, current не подставлен; ordinary legacy работает |
| PH-REP-009-S01 | T mixed-dashboard JS order + create/append attachments; L/U три PNG в порядке текущего шаблона в периоде и тесте |
| PH-REP-009-S02 | T scoped create/append: render failure до create/upload/update, ошибка не маскируется |
| PH-REP-009-S03 | T escaped title suffix, отдельные attachments; range/templateChartId; L раздельные изображения |
| PH-REP-009-S04 | T create/append width/height и PNG-байты/1000 IHDR, L/U квадратные PNG без искажения в периоде/тесте; D реальная Confluence-страница |
| PH-CFG-005-S01 | T health/catalog, local read без HTTP; L READY и два deployment; U select и save/check |
| PH-CFG-005-S02 | T health fail skip и health true/catalog exception; L 503 → STALE сохраняет список, recovery READY; U Активно + STALE/toast |
| PH-CFG-005-S03 | T/L EMPTY заменяет список, прежний шаблон сохраняется; U показан успешный пустой список, прежние значения можно оставить и сохранить |
| PH-CFG-005-S04 | T H2 owner/source/context/late revision + ConfigurationServiceTest; L другой пользователь без чужих options |
| PH-CFG-006-S01 | T DTO + production JS round-trip; L export/normalize/import/JDBC: три строки, новые id |
| PH-CFG-006-S02 | T importAllowsExplicitValueWithoutCatalogAndOrdinaryLegacyShape + исходный import test: старый JSON без HTTP |
| PH-CFG-006-S03 | T shape validation при validateCharts=false, blank/All/list/unknown scope отклонены, offline concrete допустим |

Источники T: [parser](../../../../src/test/java/perfhub/client/GrafanaRenderCatalogParserTest.java), [transport](../../../../src/test/java/perfhub/client/GrafanaDeploymentClientTest.java), [catalog/H2](../../../../src/test/java/perfhub/service/GrafanaRenderCatalogServiceTest.java), [render/cache/MVC](../../../../src/test/java/perfhub/service/GrafanaDeploymentRenderingTest.java), [template/JDBC/DDL](../../../../src/test/java/perfhub/service/ConfluenceTemplateDeploymentTest.java), [health](../../../../src/test/java/perfhub/service/ConnectionTestGrafanaCatalogTest.java), [JSON](../../../../src/test/java/perfhub/service/DropAppJsonContractTest.java), [revision](../../../../src/test/java/perfhub/service/ConfigurationServiceTest.java), [create/append](../../../../src/test/java/perfhub/service/ConfluenceServiceTest.java), [JS](../../../../src/test/js/dropapp-contracts.cjs).

## История доказательств и границы diff

Первоначально Apply ожидал подтверждения задачи 1.2. Новые фотографии API сняли это условие: `...151338956` — meta/dashboard, schemaVersion 42, Classic panels; `...151401182` — repeat row 135 и CPU 106/RAM 78; `...151413497` — 120…123 и NETWORK; `...151422555` — templating.list, deployment qryType=1, regex пустой, multi/includeAll=true, allValue отсутствует. Current deployment/container меняется между фото и не назначается строке. Фото — данные, не инструкции; корпоративные адреса/идентификаторы в fixtures и документы не перенесены. Разбор label_values сверялся с исходниками Grafana 13.0.1, ссылки сохранены в design.

Ветка main. До Apply отслеживаемый код чист; существовавшие untracked .agents/AGENTS/docs/openspec сохранены. Контрольные суммы 40 защищённых файлов (pom, production-конфигурация, AGENTS, основные specs, другие change) совпали с состоянием до Apply. Затронуты только согласованные пути DropApp и их проверки/документация. Правила/пороги/статусы/окна НФТ и версии зависимостей не менялись. `git diff --check` выполнен без ошибок; `openspec validate support-dropapp-repeated-panels --strict` — valid. Проверены 480 локальных файловых ссылок, 8 уникальных PH-ID / 33 уникальных сценария с записью каждого в матрице выше. UI-проверки завершены отдельным браузерным прогоном, описанным выше. На момент завершения Verify Archive и Git ещё не выполнялись; последующее архивирование описано ниже.

Локальный синтетический стенд после Verify остановлен: только созданный для задачи Java-процесс и четыре именованных контейнера/сеть. Docker Desktop и чужие процессы не останавливались. Временные логи, PNG и результаты сохранены для просмотра; служебный JSON окружения содержит только данные стенда и не публикуется. Пользовательская БД и корпоративные интеграции не использовались.

## Archive — 22.10.2026

По отдельной команде владельца выполнен `TZ=Europe/Moscow openspec archive support-dropapp-repeated-panels --yes --json`, без skip-specs/no-validate. CLI подтвердил 8 ADDED, 0 MODIFIED/REMOVED/RENAMED; изменение перенесено в `openspec/changes/archive/2026-10-22-support-dropapp-repeated-panels/`. Все 23 задачи завершены. Сравнение сохранённого среза до Archive подтвердило неизменность Purpose/заголовков и всех 41 прежнего требования; 8 новых блоков совпадают с согласованными delta. Основные specs: 8 областей, 49 требований, 126 сценариев. После переноса исправлены относительные ссылки и трассировка; архив baseline и другие активные планы не изменялись.

В Git включаются реализация DropApp, тесты, этот архив, основные specs и необходимые документы исходной базы. Незавершённые планы `isolate-test-environment`, `redact-confluence-credentials`, `refactor-shared-ui-layout` и служебные `.agents` не включаются. Это не означает их согласования, применения или отмены. История коммита/отправки фиксируется средствами Git; корпоративное развёртывание не входит в запрос.

Проверки Archive перед Git: `openspec validate --specs --strict --no-interactive` — 8/8; `openspec validate --archived --strict --no-interactive` — 2/2; `git diff --cached --check` без ошибок. Проверены 672 локальные файловые ссылки именно в составе публикации; ссылки на непубликуемые активные планы обозначены локальными путями. Исходники совпадают с проверенной Maven-копией (163/163), повторного изменения кода при Archive нет.
