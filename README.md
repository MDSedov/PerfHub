# Chronicler

## Пример запроса на рендеринг дашбордов Grafana

```http
POST /api/grafana/render HTTP/1.1
Host: chronicler.local
Content-Type: application/json
Authorization: Bearer <GRAFANA_API_KEY>

{
  "from": "2024-01-01T00:00:00Z",
  "to": "2024-01-01T01:00:00Z",
  "dashboards": [
    {
      "uid": "cX3F9s0Mk",
      "slug": "sales-dashboard",
      "panels": [1, 5, 9]
    }
  ]
}
```

> Список `panels` вновь содержит числовые идентификаторы панелей, для каждой из которых сервис автоматически получает заголовок и описание через REST API Grafana.
