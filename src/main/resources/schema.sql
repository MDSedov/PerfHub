CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS tests (
    test_uuid UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    start_time TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    end_time TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    test_type TEXT NOT NULL DEFAULT 'unknown',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tests_start_end UNIQUE (start_time, end_time)
);

ALTER TABLE tests
    ALTER COLUMN test_uuid SET DEFAULT gen_random_uuid();

ALTER TABLE tests
    ADD COLUMN IF NOT EXISTS test_type TEXT NOT NULL DEFAULT 'unknown';

ALTER TABLE tests
    ALTER COLUMN test_type SET DEFAULT 'unknown';

UPDATE tests
SET test_type = 'unknown'
WHERE test_type IS NULL;

ALTER TABLE tests
    ALTER COLUMN test_type SET NOT NULL;

UPDATE tests
SET test_uuid = gen_random_uuid()
WHERE test_uuid IS NULL;

CREATE TABLE IF NOT EXISTS grafana_dashboards (
    dashboard_uid TEXT PRIMARY KEY,
    slug TEXT NOT NULL,
    panels INTEGER[] NOT NULL
);

INSERT INTO grafana_dashboards (dashboard_uid, slug, panels)
VALUES ('adrr5xs', 'jmeter-metrics', ARRAY[1, 2])
ON CONFLICT (dashboard_uid) DO NOTHING;

CREATE TABLE IF NOT EXISTS configurations (
    id SERIAL PRIMARY KEY,
    service TEXT NOT NULL,
    "key" TEXT NOT NULL,
    value TEXT NOT NULL,
    description TEXT,
    CONSTRAINT uq_configurations_service_key UNIQUE (service, "key")
);

ALTER TABLE configurations
    ADD COLUMN IF NOT EXISTS description TEXT;

INSERT INTO configurations (service, "key", value, description) VALUES
    ('victoria-metrics', 'base-url', 'http://95.163.220.93:8428', 'Базовый URL инстанса VictoriaMetrics'),
    ('victoria-metrics', 'query-range-path', '/api/v1/query_range', 'Путь API для запроса диапазона метрик'),
    ('victoria-metrics', 'default-step-seconds', '15', 'Шаг агрегации метрик в секундах по умолчанию'),
    ('gigachat', 'oauth-url', 'https://ngw.devices.sberbank.ru:9443/api/v2/oauth', 'Endpoint получения OAuth-токена GigaChat'),
    ('gigachat', 'api-url', 'https://gigachat.devices.sberbank.ru/api/v1/chat/completions', 'Основной URL API GigaChat'),
    ('gigachat', 'scope', 'GIGACHAT_API_PERS', 'Значение OAuth scope для GigaChat'),
    ('gigachat', 'authorization-key', 'MDE5YTBlMWQtMzRlNi03Yzg2LWFhMzgtZDBkZTM2OWE3MDc2OjdmMzIzMTljLTA1MGEtNDkxMC1iZDY5LTQ1MGZkN2IzN2YxOQ==', 'Base64 ключ авторизации клиента GigaChat'),
    ('gigachat', 'client-id', '019a0e1d-34e6-7c86-aa38-d0de369a7076', 'Идентификатор клиента для GigaChat'),
    ('gigachat', 'grant-type', 'client_credentials', 'Тип grant''а при OAuth-авторизации GigaChat'),
    ('gigachat', 'model', 'GigaChat-2-Max', 'Название модели GigaChat для генерации ответов'),
    ('gigachat', 'disable-ssl-validation', 'true', 'Флаг отключения проверки SSL при обращении к GigaChat'),
    ('grafana', 'base-url', 'http://95.163.220.93:3000', 'Базовый URL панели Grafana'),
    ('grafana', 'render-path', '/render/d-solo', 'Путь API Grafana для рендеринга панелей'),
    ('grafana', 'api-key', 'glsa_Mqy9asYfNmvmy76EMSEkL0pGeqlwvnT4_32995c7a', 'API-ключ доступа к Grafana'),
    ('grafana', 'image-width', '1000', 'Ширина рендеримых изображений Grafana'),
    ('grafana', 'image-height', '500', 'Высота рендеримых изображений Grafana'),
    ('grafana', 'timezone', 'Europe/Moscow', 'Таймзона для отображения данных Grafana'),
    ('confluence', 'base-url', 'http://95.163.220.93:8090', 'Базовый URL портала Confluence'),
    ('confluence', 'personal-access-token', 'MDAxNTA0MjY1MzI2Ot9WllQZDPt7+VDKPR2HieuP9owM', 'Токен доступа к Confluence')
ON CONFLICT (service, "key") DO UPDATE
SET value = EXCLUDED.value,
    description = EXCLUDED.description;

CREATE TABLE IF NOT EXISTS victoria_metrics_queries (
    id SERIAL PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    expression TEXT NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO victoria_metrics_queries (code, expression, description) VALUES
    ('intensity', 'jmeterall.a.count', 'Суммарная интенсивность запросов'),
    ('response_time_90', 'jmeterall.a.pct90', '90-й перцентиль времени отклика'),
    ('response_time_95', 'jmeterall.a.pct95', '95-й перцентиль времени отклика'),
    ('response_time_99', 'jmeterall.a.pct99', '99-й перцентиль времени отклика'),
    ('request_total', 'sum_over_time(jmeterall.a.count[5m])', 'Общее число запросов за интервал'),
    ('request_error', 'sum_over_time(jmeterall.a.count{status="ko"}[5m])', 'Число ошибочных запросов за интервал'),
    ('container_cpu_utilization', '100 * max_over_time(container_cpu_usage_seconds_total{namespace="testing"}[5m])', 'Процент утилизации CPU контейнеров'),
    ('container_memory_utilization', '100 * max_over_time(container_memory_usage_bytes{namespace="testing"}[5m]) / max_over_time(container_spec_memory_limit_bytes{namespace="testing"}[5m])', 'Процент утилизации памяти контейнеров'),
    ('pod_restarts', 'increase(kube_pod_container_status_restarts_total{namespace="testing"}[5m])', 'Число рестартов pod')
ON CONFLICT (code) DO UPDATE
SET
    expression = EXCLUDED.expression,
    description = EXCLUDED.description,
    updated_at = NOW();

CREATE TABLE IF NOT EXISTS non_functional_requirements (
    id SERIAL PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    metric_type TEXT NOT NULL,
    threshold_value DOUBLE PRECISION NOT NULL,
    threshold_unit TEXT NOT NULL,
    description_ru TEXT,
    description_en TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS test_requirement_evaluations (
    id BIGSERIAL PRIMARY KEY,
    test_uuid UUID NOT NULL REFERENCES tests(test_uuid) ON DELETE CASCADE,
    requirement_id INTEGER NOT NULL REFERENCES non_functional_requirements(id),
    status TEXT NOT NULL,
    measured_value DOUBLE PRECISION,
    unit TEXT,
    details JSONB,
    evaluated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (test_uuid, requirement_id)
);

INSERT INTO non_functional_requirements (
    code,
    metric_type,
    threshold_value,
    threshold_unit,
    description_ru,
    description_en,
    parameters
) VALUES
    ('LATENCY_P90', 'LATENCY_P90', 1000.0, 'milliseconds',
        '90-й перцентиль времени отклика должен быть < 1000 миллисекунд',
        '90th percentile latency must stay under 1000 milliseconds',
        '{"query":"response_time_90"}'),
    ('ERROR_RATE', 'ERROR_RATE', 1.0, 'percent',
        'Доля ошибок за тест должна быть < 1% от общего числа запросов',
        'Total error rate during the test must be below 1%',
        '{"totalQuery":"request_total","errorQuery":"request_error"}'),
    ('CPU_UTILIZATION', 'CPU_UTILIZATION', 80.0, 'percent',
        'Утилизация CPU каждого контейнера должна быть < 80%',
        'CPU utilization of each container must stay below 80%',
        '{"query":"container_cpu_utilization","groupLabel":"container"}'),
    ('MEMORY_UTILIZATION', 'MEMORY_UTILIZATION', 80.0, 'percent',
        'Утилизация RAM каждого контейнера должна быть < 80%',
        'Memory utilization of each container must stay below 80%',
        '{"query":"container_memory_utilization","groupLabel":"container"}'),
    ('POD_RESTARTS', 'POD_RESTARTS', 0.0, 'count',
        'Во время теста не должно быть рестартов pod',
        'Pods must not restart during the test window',
        '{"query":"pod_restarts","groupLabel":"pod"}')
ON CONFLICT (code) DO UPDATE
SET
    metric_type = EXCLUDED.metric_type,
    threshold_value = EXCLUDED.threshold_value,
    threshold_unit = EXCLUDED.threshold_unit,
    description_ru = EXCLUDED.description_ru,
    description_en = EXCLUDED.description_en,
    parameters = EXCLUDED.parameters,
    updated_at = NOW();
