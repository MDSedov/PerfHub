(function () {
    const DEFAULT_INTERVAL_MS = 30000;
    const STORAGE_KEY = '__chroniclerBackgroundErrorPolling';

    async function fetchAndShow(showToast) {
        try {
            const response = await fetch('/api/background/errors');
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

    function startPolling(showToast, intervalMs = DEFAULT_INTERVAL_MS) {
        if (typeof showToast !== 'function') {
            return;
        }
        const existing = window[STORAGE_KEY];
        if (existing?.timerId) {
            return;
        }
        const timerId = setInterval(() => fetchAndShow(showToast), intervalMs);
        window[STORAGE_KEY] = { timerId };
        fetchAndShow(showToast);
    }

    window.startBackgroundErrorPolling = startPolling;
})();
