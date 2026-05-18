(function () {
    const DEFAULT_INTERVAL_MS = 30000;
    const STORAGE_KEY = '__perfhubBackgroundErrorPolling';

    function buildErrorsUrl(scope) {
        const normalizedScope = typeof scope === 'string' ? scope.trim() : '';
        if (!normalizedScope) {
            return '/api/background/errors';
        }
        return `/api/background/errors?scope=${encodeURIComponent(normalizedScope)}`;
    }

    async function fetchAndShow(showToast, scope) {
        try {
            const response = await fetch(buildErrorsUrl(scope));
            if (!response.ok) {
                throw new Error('Ответ сервера: ' + response.status);
            }
            const errors = await response.json();
            if (Array.isArray(errors)) {
                errors.forEach(item => {
                    const message = item?.message || 'Ошибка фонового процесса';
                    showToast(message, 'danger', 20000);
                });
            }
        } catch (error) {
            // eslint-disable-next-line no-console
            console.error('Не удалось получить сообщения о фоновых ошибках', error);
        }
    }

    function normalizeOptions(options) {
        if (typeof options === 'number') {
            return {intervalMs: options, scope: ''};
        }
        if (!options || typeof options !== 'object') {
            return {intervalMs: DEFAULT_INTERVAL_MS, scope: ''};
        }
        return {
            intervalMs: Number.isFinite(options.intervalMs) ? options.intervalMs : DEFAULT_INTERVAL_MS,
            scope: typeof options.scope === 'string' ? options.scope.trim() : ''
        };
    }

    function startPolling(showToast, options = {}) {
        if (typeof showToast !== 'function') {
            return;
        }
        const {intervalMs, scope} = normalizeOptions(options);
        const storageKey = `${STORAGE_KEY}:${scope || 'global'}`;
        const existing = window[storageKey];
        if (existing?.timerId) {
            return;
        }
        const timerId = setInterval(() => fetchAndShow(showToast, scope), intervalMs);
        window[storageKey] = { timerId };
        fetchAndShow(showToast, scope);
    }

    window.startBackgroundErrorPolling = startPolling;
})();
