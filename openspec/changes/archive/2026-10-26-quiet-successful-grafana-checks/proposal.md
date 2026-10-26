# Proposal

## Why

26.10.2026 владелец подтвердил восстановление deployment после исправления сохранённого UID datasource и попросил убрать «Каталог: READY…» и диагностический лог. Затем явно уточнил: «Диагностический файл тоже не пиши. Согласовано. Реализуй». Review/Apply получены для этого объёма, повторное согласование удаления файла не требуется.

**A:** settings-connections.html независимо показывает toast catalog.message и подпись состояния. GrafanaCatalogDiagnosticsService пишет рутинное INFO, WARN ошибок и файл, добавляя путь/ID в catalog.message. Эти каналы разделены.

## What Changes

- Убрать подпись, дату/tooltip и отдельный toast READY/EMPTY на странице подключений; обычный результат подключения и предупреждения PARTIAL/STALE/UNAVAILABLE/unknown остаются.
- Убрать рутинное INFO-резюме и все операции создания/обновления диагностического файла, включая временные файлы. Ранее созданные отчёты не удалять и не изменять.
- Возвращать исходный catalog.message без пути, ID проверки и сообщения о недоступном файле. Сохранить безопасные WARN фактических отказов каталога.
- Продолжать обновлять каталог и deployment; оставить JSON-поля catalog.status/message/updatedAt, health-результат, owner/revision/context и fallback без изменения.

## Capabilities

### New Capabilities

Нет.

### Modified Capabilities

- `connection-settings`: MODIFIED PH-CFG-005 для успешного вывода READY/EMPTY; ADDED PH-CFG-008 для проверки без рутинного INFO и файлов диагностики. PH-CFG-008 явно заменяет автоматическое сохранение/путь по применённому PH-CFG-007.

## Impact

Production: шаблон подключений и GrafanaCatalogDiagnosticsService. Удаляется недостижимый после отказа от записи файлов код и внутренние зависимости пути/пользователя для targeting отчёта; относящиеся Java-тесты обновляются с сохранением HTTP/snapshot/fallback-покрытия. Это функциональное изменение по прямому запросу владельца.

PH-CFG-005-S03 намеренно уточняется: EMPTY остаётся в API/редакторе, но без отдельной успешной подписи на странице подключений. PH-CFG-007 находится в применённом, не архивированном diagnose-grafana-catalog-failures: его исторические артефакты сохраняются, новое согласование прекращает автоматический файл и дополнение message. Безопасные ошибки и PH-GRAF-010 остаются. Основные specs напрямую не меняются; будущий Archive должен учитывать порядок исторических delta.

Вне области: удаление старых отчётов, скрытие ошибок, новые logging-флаги/эндпоинты, изменение datasource/selector, HTTP-последовательности, retry/timeout, API shape, SQL, зависимостей, НФТ и рендера. Archive/Git/deployment не разрешены этой командой.

Материалы: [specs](specs/connection-settings/spec.md), [design](design.md), [tasks](tasks.md), результаты — [verification.md](verification.md).
