(function () {
    const luckyReportsButton = document.getElementById('luckyReportsButton');
    if (!luckyReportsButton) {
        return;
    }

    const luckyReportsModalElement = document.getElementById('luckyReportsModal');
    const luckyReportsModalForm = document.getElementById('luckyReportsModalForm');
    const luckyReportsModalSubtitle = document.getElementById('luckyReportsModalSubtitle');
    const luckyReportsParentPageIdInput = document.getElementById('luckyReportsParentPageId');
    const luckyReportsParentPageRefreshButton = document.getElementById('luckyReportsParentPageRefreshButton');
    const luckyReportsCreateReleasePageCheckbox = document.getElementById('luckyReportsCreateReleasePage');
    const luckyReportsSelectedCounter = document.getElementById('luckyReportsSelectedCounter');
    const luckyReportsSelectedSubtitle = document.getElementById('luckyReportsSelectedSubtitle');
    const luckyReportsSelectedWrapper = document.getElementById('luckyReportsSelectedWrapper');
    const luckyReportsSelectedBody = document.getElementById('luckyReportsSelectedBody');
    const luckyReportsSelectedEmpty = document.getElementById('luckyReportsSelectedEmpty');
    const luckyReportsResultsWrapper = document.getElementById('luckyReportsResultsWrapper');
    const luckyReportsResultsBody = document.getElementById('luckyReportsResultsBody');
    const luckyReportsResultsEmpty = document.getElementById('luckyReportsResultsEmpty');
    const luckyReportsCreateButton = document.getElementById('luckyReportsCreateButton');
    const toastContainer = document.getElementById('toastContainer');

    const TEST_TYPE_ALIASES = {
        confirm: 'stability'
    };

    const TYPE_LABELS = {
        max: 'MAX',
        stability: 'STABILITY',
        soak: 'SOAK',
        psu: 'PSU',
        dt: 'DT',
        unknown: 'UNKNOWN'
    };

    const ACTIVE_TEST_END_GRACE_MS = 3 * 60 * 1000;
    const RELEASE_REPORT_RESULT_ID = '__release_report__';

    let tests = [];
    let latestReleaseValue = null;
    let latestReleaseTestIds = [];
    let reportTemplates = [];
    let reportTemplatesLoaded = false;
    let reportTemplatesPromise = null;
    let selectedReportTemplateIds = new Map();
    let confluenceParentPageOptions = [];
    let confluenceParentPagesLoading = false;
    let confluenceParentPagesErrorMessage = '';
    let confluenceParentPagesPromise = null;
    let selectedReportParentPageId = '';
    let reportGenerationResults = [];
    let isReportCreationInProgress = false;
    let luckyReportsModalBackdrop = null;

    function removeToast(toast) {
        if (!toast) {
            return;
        }
        const timerId = toast.dataset.timerId;
        if (timerId) {
            clearTimeout(Number(timerId));
        }
        toast.remove();
    }

    function showToast(message, type = 'info', durationMs = 12000) {
        if (!toastContainer) {
            return null;
        }
        const toast = document.createElement('div');
        toast.className = `toast-item ${type}`;
        const body = document.createElement('p');
        body.className = 'toast-body';
        body.textContent = message;
        const close = document.createElement('button');
        close.type = 'button';
        close.className = 'toast-close';
        close.setAttribute('aria-label', 'Закрыть уведомление');
        close.innerHTML = '&times;';
        close.addEventListener('click', () => removeToast(toast));
        const content = document.createElement('div');
        content.className = 'flex-grow-1';
        content.appendChild(body);
        toast.appendChild(content);
        toast.appendChild(close);
        toastContainer.appendChild(toast);
        const timer = setTimeout(() => removeToast(toast), durationMs);
        toast.dataset.timerId = timer.toString();
        return toast;
    }

    if (toastContainer) {
        toastContainer.addEventListener('click', event => {
            const closeButton = event.target.closest('.toast-close');
            if (!closeButton) {
                return;
            }
            const toast = closeButton.closest('.toast-item');
            removeToast(toast);
        });
    }

    function extractReportCreationErrorMessage(errorText, fallback = 'Не удалось создать отчеты') {
        if (!errorText) {
            return fallback;
        }
        try {
            const parsed = JSON.parse(errorText);
            if (typeof parsed?.message === 'string' && parsed.message.trim() !== '') {
                return parsed.message.trim();
            }
        } catch (parseError) {
            // ignore malformed JSON and fallback to raw text
        }
        const text = String(errorText).trim();
        return text === '' ? fallback : text;
    }

    function normalizeReleaseValue(value) {
        if (value === undefined || value === null) {
            return null;
        }
        const text = String(value).trim();
        return text === '' ? null : text;
    }

    function splitReleaseSegments(value) {
        return String(value)
            .split(/[._-]+/)
            .map(segment => segment.trim())
            .filter(segment => segment !== '');
    }

    function isNumericReleaseSegment(segment) {
        return /^\d+$/.test(segment);
    }

    function normalizeNumericReleaseSegment(segment) {
        return String(segment).replace(/^0+(?=\d)/, '');
    }

    function compareNumericReleaseSegments(left, right) {
        const normalizedLeft = normalizeNumericReleaseSegment(left);
        const normalizedRight = normalizeNumericReleaseSegment(right);
        if (normalizedLeft.length !== normalizedRight.length) {
            return normalizedLeft.length - normalizedRight.length;
        }
        return normalizedLeft.localeCompare(normalizedRight, 'en');
    }

    function compareMissingReleaseSegment(actualSegment) {
        if (actualSegment === undefined || actualSegment === null) {
            return 0;
        }
        if (isNumericReleaseSegment(actualSegment)) {
            return compareNumericReleaseSegments(actualSegment, '0') === 0 ? 0 : -1;
        }
        return 1;
    }

    function compareReleaseValues(left, right) {
        const normalizedLeft = normalizeReleaseValue(left);
        const normalizedRight = normalizeReleaseValue(right);
        if (normalizedLeft === null) {
            return normalizedRight === null ? 0 : -1;
        }
        if (normalizedRight === null) {
            return 1;
        }

        const leftSegments = splitReleaseSegments(normalizedLeft);
        const rightSegments = splitReleaseSegments(normalizedRight);
        const maxSegments = Math.max(leftSegments.length, rightSegments.length);
        for (let index = 0; index < maxSegments; index += 1) {
            const leftSegment = leftSegments[index];
            const rightSegment = rightSegments[index];

            let comparison = 0;
            if (leftSegment === undefined || rightSegment === undefined) {
                comparison = leftSegment === undefined
                    ? compareMissingReleaseSegment(rightSegment)
                    : -compareMissingReleaseSegment(leftSegment);
            } else {
                const leftNumeric = isNumericReleaseSegment(leftSegment);
                const rightNumeric = isNumericReleaseSegment(rightSegment);
                if (leftNumeric && rightNumeric) {
                    comparison = compareNumericReleaseSegments(leftSegment, rightSegment);
                } else if (leftNumeric !== rightNumeric) {
                    comparison = leftNumeric ? 1 : -1;
                } else {
                    comparison = leftSegment.localeCompare(rightSegment, 'en', { sensitivity: 'accent' });
                }
            }

            if (comparison !== 0) {
                return comparison;
            }
        }

        return normalizedLeft.localeCompare(normalizedRight, 'en', { sensitivity: 'accent' });
    }

    function getReleaseValue(test) {
        return normalizeReleaseValue(test?.release);
    }

    function findLatestRelease(testsList) {
        let maxRelease = null;
        for (const test of testsList) {
            const release = getReleaseValue(test);
            if (release && (maxRelease === null || compareReleaseValues(release, maxRelease) > 0)) {
                maxRelease = release;
            }
        }
        return maxRelease;
    }

    function canonicalizeTestType(type) {
        const normalized = (type ?? '').toString().trim().toLowerCase();
        return TEST_TYPE_ALIASES[normalized] ?? normalized;
    }

    function normalizeTestType(type) {
        const normalized = canonicalizeTestType(type);
        if (normalized && TYPE_LABELS[normalized]) {
            return TYPE_LABELS[normalized];
        }
        if (normalized) {
            return normalized.toUpperCase();
        }
        return TYPE_LABELS.unknown;
    }

    function parseMoscowDate(value) {
        if (!value) {
            return null;
        }
        return new Date(value.replace(' ', 'T') + '+03:00');
    }

    function formatDateNoSeconds(value) {
        if (!value) {
            return '—';
        }
        const date = parseMoscowDate(value);
        if (!date || Number.isNaN(date.getTime())) {
            return value;
        }
        const year = date.getFullYear();
        const month = String(date.getMonth() + 1).padStart(2, '0');
        const day = String(date.getDate()).padStart(2, '0');
        const hours = String(date.getHours()).padStart(2, '0');
        const minutes = String(date.getMinutes()).padStart(2, '0');
        return `${year}-${month}-${day} ${hours}:${minutes}`;
    }

    function isTestCurrentlyActive(test) {
        const now = new Date();
        const start = parseMoscowDate(test?.start);
        const end = parseMoscowDate(test?.end);
        if (start && now < start) {
            return false;
        }
        if (end) {
            return now.getTime() <= end.getTime() + ACTIVE_TEST_END_GRACE_MS;
        }
        return Boolean(start);
    }

    function formatTestEndValue(test) {
        if (isTestCurrentlyActive(test)) {
            return 'В процессе...';
        }
        return formatDateNoSeconds(test?.end);
    }

    function findTestById(testId) {
        return tests.find(test => test?.id === testId) ?? null;
    }

    function getSelectedLuckyTests() {
        const selectedTests = [];
        latestReleaseTestIds.forEach(testId => {
            const test = findTestById(testId);
            if (test) {
                selectedTests.push(test);
            }
        });
        return selectedTests;
    }

    function getReportTemplateType(template) {
        return String(template?.templateType ?? 'TEST').trim().toUpperCase();
    }

    function getReportTemplatesByType(templateType) {
        const normalizedType = String(templateType ?? '').trim().toUpperCase();
        return reportTemplates.filter(template => getReportTemplateType(template) === normalizedType);
    }

    function getTestReportTemplates() {
        return getReportTemplatesByType('TEST');
    }

    function getReleaseReportTemplates() {
        return getReportTemplatesByType('RELEASE');
    }

    function isKnownReportTemplateId(templateId) {
        return getTestReportTemplates().some(template => String(template?.id) === String(templateId));
    }

    function resolveDefaultReportTemplateId(test) {
        const normalizedType = canonicalizeTestType(test?.type) || 'unknown';
        const matchedTemplate = getTestReportTemplates().find(template =>
                (template?.shortName ?? '').trim().toLowerCase() === `${normalizedType}_template`);
        return matchedTemplate?.id ? String(matchedTemplate.id) : '';
    }

    function ensureSelectedReportTemplate(test) {
        const testId = test?.id;
        if (!testId) {
            return '';
        }
        const currentTemplateId = selectedReportTemplateIds.get(testId);
        if (currentTemplateId && isKnownReportTemplateId(currentTemplateId)) {
            return currentTemplateId;
        }
        const defaultTemplateId = resolveDefaultReportTemplateId(test);
        if (defaultTemplateId) {
            selectedReportTemplateIds.set(testId, defaultTemplateId);
            return defaultTemplateId;
        }
        selectedReportTemplateIds.delete(testId);
        return '';
    }

    function getSelectedTestsMissingTemplate() {
        return getSelectedLuckyTests().filter(test => {
            if (!test?.id) {
                return true;
            }
            const templateId = resolveDefaultReportTemplateId(test);
            return !templateId || !isKnownReportTemplateId(templateId);
        });
    }

    function buildLuckyReportsSelectedSubtitle() {
        if (!latestReleaseValue) {
            return 'Список тестов для формирования отчетов в Confluence.';
        }
        return `Автоматически выбраны все тесты последнего релиза ${latestReleaseValue}.`;
    }

    function updateLuckyReportsHeadings() {
        if (luckyReportsModalSubtitle) {
            luckyReportsModalSubtitle.textContent = latestReleaseValue
                    ? `Создайте страницы Confluence по всем тестам последнего релиза ${latestReleaseValue}.`
                    : 'Создайте страницы Confluence по всем тестам последнего релиза.';
        }
        if (luckyReportsSelectedSubtitle) {
            luckyReportsSelectedSubtitle.textContent = buildLuckyReportsSelectedSubtitle();
        }
    }

    function appendGenerateReportsTextCell(row, text, className = '') {
        const cell = document.createElement('td');
        if (className) {
            cell.className = className;
        }
        cell.textContent = text;
        row.appendChild(cell);
        return cell;
    }

    function renderSelectedTests() {
        if (!luckyReportsSelectedBody || !luckyReportsSelectedWrapper || !luckyReportsSelectedEmpty) {
            return;
        }
        const selectedTests = getSelectedLuckyTests();
        luckyReportsSelectedBody.innerHTML = '';
        if (luckyReportsSelectedCounter) {
            luckyReportsSelectedCounter.textContent = selectedTests.length.toString();
        }
        if (selectedTests.length === 0) {
            luckyReportsSelectedWrapper.classList.add('d-none');
            luckyReportsSelectedEmpty.classList.remove('d-none');
            return;
        }

        luckyReportsSelectedWrapper.classList.remove('d-none');
        luckyReportsSelectedEmpty.classList.add('d-none');

        selectedTests.forEach(test => {
            const row = document.createElement('tr');
            appendGenerateReportsTextCell(row, getReleaseValue(test) ?? '—');

            const typeCell = document.createElement('td');
            const typeBadge = document.createElement('span');
            typeBadge.className = 'badge text-bg-light text-success badge-test-type';
            typeBadge.textContent = normalizeTestType(test?.type);
            typeCell.appendChild(typeBadge);
            row.appendChild(typeCell);

            appendGenerateReportsTextCell(row, formatDateNoSeconds(test?.start), 'text-nowrap');
            appendGenerateReportsTextCell(row, formatTestEndValue(test), 'text-nowrap');

            luckyReportsSelectedBody.appendChild(row);
        });
    }

    function createReportGenerationStatusBadge(status) {
        const badge = document.createElement('span');
        badge.className = 'badge rounded-pill report-generation-status-badge';
        if (status === 'success') {
            badge.classList.add('text-bg-success');
            badge.textContent = 'Создан';
            return badge;
        }
        if (status === 'error') {
            badge.classList.add('text-bg-danger');
            badge.textContent = 'Ошибка';
            return badge;
        }
        if (status === 'running') {
            badge.classList.add('text-bg-primary');
            badge.textContent = 'Создаем';
            return badge;
        }
        badge.classList.add('text-bg-secondary');
        badge.textContent = 'Ожидает';
        return badge;
    }

    function buildReportGenerationContextLabel(test) {
        if (!test) {
            return '';
        }
        const parts = [];
        const release = getReleaseValue(test);
        if (release) {
            parts.push(`Релиз ${release}`);
        }
        parts.push(normalizeTestType(test?.type));
        const start = formatDateNoSeconds(test?.start);
        if (start && start !== '—') {
            parts.push(start);
        }
        return parts.join(' • ');
    }

    function renderGenerationResults() {
        if (!luckyReportsResultsBody || !luckyReportsResultsWrapper || !luckyReportsResultsEmpty) {
            return;
        }
        luckyReportsResultsBody.innerHTML = '';
        if (reportGenerationResults.length === 0) {
            luckyReportsResultsWrapper.classList.add('d-none');
            luckyReportsResultsEmpty.textContent = isReportCreationInProgress
                    ? 'Подготавливаем запуск генерации…'
                    : 'Генерация еще не запускалась.';
            luckyReportsResultsEmpty.classList.remove('d-none');
            return;
        }

        luckyReportsResultsEmpty.classList.add('d-none');
        luckyReportsResultsWrapper.classList.remove('d-none');

        reportGenerationResults.forEach(result => {
            const row = document.createElement('tr');
            appendGenerateReportsTextCell(row, String(result.order), 'text-nowrap');

            const statusCell = document.createElement('td');
            statusCell.appendChild(createReportGenerationStatusBadge(result.status));
            row.appendChild(statusCell);

            const reportCell = document.createElement('td');
            reportCell.className = 'report-generation-result-cell';
            if (result.status === 'success' && result.pageUrl) {
                const link = document.createElement('a');
                link.href = result.pageUrl;
                link.target = '_blank';
                link.rel = 'noopener noreferrer';
                link.className = 'report-generation-result-link';
                link.textContent = result.pageTitle || `Отчет ${result.order}`;
                reportCell.appendChild(link);
            } else if (result.status === 'success') {
                reportCell.textContent = result.pageTitle || `Отчет ${result.order}`;
            } else if (result.status === 'error') {
                reportCell.textContent = result.message || 'Не удалось создать отчет';
            } else if (result.status === 'running') {
                reportCell.textContent = 'Создаем страницу в Confluence…';
            } else {
                reportCell.textContent = 'Ожидает запуска';
            }

            if (result.contextLabel) {
                const context = document.createElement('div');
                context.className = 'report-generation-result-note';
                context.textContent = result.contextLabel;
                reportCell.appendChild(context);
            }
            row.appendChild(reportCell);
            luckyReportsResultsBody.appendChild(row);
        });
    }

    function updateReportGenerationResult(testId, patch) {
        const resultIndex = reportGenerationResults.findIndex(item => item.testId === testId);
        if (resultIndex < 0) {
            return;
        }
        reportGenerationResults[resultIndex] = {
            ...reportGenerationResults[resultIndex],
            ...patch
        };
        renderGenerationResults();
    }

    function buildConfluenceParentPageOptionLabel(page, depth = 0) {
        const title = (page?.title ?? '').trim() || `Страница ${page?.id ?? ''}`.trim();
        return `${'-- '.repeat(depth)}${title}`;
    }

    function flattenConfluenceParentPages(page, depth = 0, result = []) {
        if (!page?.id) {
            return result;
        }
        result.push({
            id: String(page.id),
            label: buildConfluenceParentPageOptionLabel(page, depth)
        });
        if (Array.isArray(page.children)) {
            page.children.forEach(child => flattenConfluenceParentPages(child, depth + 1, result));
        }
        return result;
    }

    function renderConfluenceParentPageOptions() {
        if (!luckyReportsParentPageIdInput) {
            return;
        }

        if (luckyReportsParentPageRefreshButton) {
            luckyReportsParentPageRefreshButton.disabled = confluenceParentPagesLoading;
        }

        luckyReportsParentPageIdInput.innerHTML = '';

        if (confluenceParentPagesLoading) {
            const loadingOption = document.createElement('option');
            loadingOption.value = '';
            loadingOption.textContent = 'Загружаем страницы Confluence…';
            luckyReportsParentPageIdInput.appendChild(loadingOption);
            luckyReportsParentPageIdInput.disabled = true;
            updateSubmitState();
            return;
        }

        if (confluenceParentPagesErrorMessage) {
            const errorOption = document.createElement('option');
            errorOption.value = '';
            errorOption.textContent = confluenceParentPagesErrorMessage;
            luckyReportsParentPageIdInput.appendChild(errorOption);
            luckyReportsParentPageIdInput.disabled = true;
            updateSubmitState();
            return;
        }

        if (confluenceParentPageOptions.length === 0) {
            const emptyOption = document.createElement('option');
            emptyOption.value = '';
            emptyOption.textContent = 'Страницы Confluence не найдены';
            luckyReportsParentPageIdInput.appendChild(emptyOption);
            luckyReportsParentPageIdInput.disabled = true;
            updateSubmitState();
            return;
        }

        confluenceParentPageOptions.forEach(page => {
            const option = document.createElement('option');
            option.value = page.id;
            option.textContent = page.label;
            luckyReportsParentPageIdInput.appendChild(option);
        });

        luckyReportsParentPageIdInput.disabled = false;
        const nextValue = confluenceParentPageOptions.some(page => page.id === selectedReportParentPageId)
                ? selectedReportParentPageId
                : confluenceParentPageOptions[0].id;
        luckyReportsParentPageIdInput.value = nextValue;
        selectedReportParentPageId = nextValue;
        updateSubmitState();
    }

    function refreshLuckyReportsModalState() {
        updateLuckyReportsHeadings();
        renderSelectedTests();
        updateSubmitState();
    }

    async function loadConfluenceParentPages(forceReload = false) {
        if (!luckyReportsParentPageIdInput) {
            return;
        }
        if (!forceReload && confluenceParentPageOptions.length > 0 && !confluenceParentPagesErrorMessage) {
            renderConfluenceParentPageOptions();
            return;
        }
        if (confluenceParentPagesPromise) {
            return confluenceParentPagesPromise;
        }

        selectedReportParentPageId = selectedReportParentPageId || (luckyReportsParentPageIdInput.value ?? '').trim();
        confluenceParentPagesLoading = true;
        confluenceParentPagesErrorMessage = '';
        renderConfluenceParentPageOptions();

        confluenceParentPagesPromise = (async () => {
            try {
                const response = await fetch('/api/confluence/report/parent-pages/', {
                    cache: 'no-store'
                });
                if (!response.ok) {
                    throw new Error(
                            extractReportCreationErrorMessage(
                                    await response.text(),
                                    'Не удалось загрузить список страниц Confluence'));
                }
                const payload = await response.json();
                confluenceParentPageOptions = flattenConfluenceParentPages(payload);
                if (confluenceParentPageOptions.length === 0) {
                    confluenceParentPagesErrorMessage = 'В Confluence не найдены доступные страницы';
                }
            } catch (error) {
                confluenceParentPageOptions = [];
                confluenceParentPagesErrorMessage = error?.message || 'Не удалось загрузить список страниц Confluence';
                showToast(confluenceParentPagesErrorMessage, 'danger', 12000);
            } finally {
                confluenceParentPagesLoading = false;
                confluenceParentPagesPromise = null;
                renderConfluenceParentPageOptions();
            }
        })();

        return confluenceParentPagesPromise;
    }

    async function loadReportTemplates(forceReload = false) {
        if (!forceReload && reportTemplatesLoaded) {
            refreshLuckyReportsModalState();
            return;
        }
        if (reportTemplatesPromise) {
            return reportTemplatesPromise;
        }

        reportTemplatesLoaded = false;
        reportTemplatesPromise = (async () => {
            try {
                const response = await fetch('/api/settings/confluence-templates');
                if (!response.ok) {
                    const errorText = await response.text();
                    throw new Error(extractReportCreationErrorMessage(errorText, 'Не удалось загрузить шаблоны отчетов'));
                }
                const payload = await response.json();
                reportTemplates = Array.isArray(payload) ? payload : [];
            } catch (error) {
                reportTemplates = [];
                showToast(error?.message || 'Не удалось загрузить шаблоны отчетов', 'warning');
            } finally {
                reportTemplatesLoaded = true;
                reportTemplatesPromise = null;
                latestReleaseTestIds.forEach(testId => {
                    const test = findTestById(testId);
                    if (test) {
                        ensureSelectedReportTemplate(test);
                    }
                });
                refreshLuckyReportsModalState();
            }
        })();

        return reportTemplatesPromise;
    }

    async function loadTests() {
        const response = await fetch('/api/tests');
        if (!response.ok) {
            throw new Error(extractReportCreationErrorMessage(await response.text(), 'Не удалось получить список тестов'));
        }
        const payload = await response.json();
        tests = Array.isArray(payload) ? payload : [];
        return tests;
    }

    function syncLatestReleaseSelection(latestRelease) {
        latestReleaseValue = latestRelease;
        latestReleaseTestIds = tests
                .filter(test => getReleaseValue(test) === latestRelease)
                .map(test => test.id)
                .filter(Boolean);

        const selectedIdSet = new Set(latestReleaseTestIds);
        selectedReportTemplateIds.forEach((value, key) => {
            if (!selectedIdSet.has(key)) {
                selectedReportTemplateIds.delete(key);
            }
        });
        latestReleaseTestIds.forEach(testId => {
            const test = findTestById(testId);
            if (test) {
                ensureSelectedReportTemplate(test);
            }
        });
    }

    function getLuckyReportsModalInstance() {
        if (!luckyReportsModalElement || !window.bootstrap?.Modal) {
            return null;
        }
        return bootstrap.Modal.getOrCreateInstance(luckyReportsModalElement);
    }

    function removeLuckyReportsModalBackdrop() {
        if (luckyReportsModalBackdrop?.isConnected) {
            luckyReportsModalBackdrop.remove();
        }
        luckyReportsModalBackdrop = null;
    }

    function hideLuckyReportsModalFallback() {
        if (!luckyReportsModalElement) {
            return;
        }
        luckyReportsModalElement.classList.remove('show');
        luckyReportsModalElement.style.display = 'none';
        luckyReportsModalElement.setAttribute('aria-hidden', 'true');
        luckyReportsModalElement.removeAttribute('aria-modal');
        document.body.classList.remove('modal-open');
        removeLuckyReportsModalBackdrop();
        updateSubmitState();
    }

    function showLuckyReportsModalDialog() {
        const modalInstance = getLuckyReportsModalInstance();
        if (modalInstance) {
            modalInstance.show();
            return {
                handleUpdate: () => modalInstance.handleUpdate?.()
            };
        }

        if (!luckyReportsModalElement) {
            return null;
        }

        luckyReportsModalElement.style.display = 'block';
        luckyReportsModalElement.classList.add('show');
        luckyReportsModalElement.removeAttribute('aria-hidden');
        luckyReportsModalElement.setAttribute('aria-modal', 'true');
        document.body.classList.add('modal-open');
        removeLuckyReportsModalBackdrop();
        const backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop fade show';
        document.body.appendChild(backdrop);
        luckyReportsModalBackdrop = backdrop;
        return {
            handleUpdate: () => {}
        };
    }

    function openLuckyReportsModal() {
        refreshLuckyReportsModalState();
        renderGenerationResults();
        const modalApi = showLuckyReportsModalDialog();
        window.requestAnimationFrame(() => modalApi?.handleUpdate?.());
        void Promise.all([loadReportTemplates(), loadConfluenceParentPages(true)]).finally(() => {
            modalApi?.handleUpdate?.();
            if (luckyReportsModalElement?.classList.contains('show')) {
                luckyReportsParentPageIdInput?.focus();
            }
        });
    }

    function updateSubmitState() {
        if (!luckyReportsCreateButton) {
            return;
        }
        const hasParentPage = (luckyReportsParentPageIdInput?.value ?? '').trim().length > 0;
        const hasSelectedTests = latestReleaseTestIds.length > 0;
        const hasTemplatesForAllSelectedTests =
                reportTemplatesLoaded
                && getTestReportTemplates().length > 0
                && getSelectedTestsMissingTemplate().length === 0;
        luckyReportsCreateButton.disabled =
                isReportCreationInProgress
                || !hasParentPage
                || !hasSelectedTests
                || !hasTemplatesForAllSelectedTests;
    }

    async function fetchWithTimeout(url, options = {}, timeoutMs = 300000) {
        if (typeof AbortController !== 'function') {
            return fetch(url, options);
        }
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), timeoutMs);
        try {
            return await fetch(url, {
                ...options,
                signal: controller.signal
            });
        } catch (error) {
            if (error?.name === 'AbortError') {
                throw new Error('Операция выполняется слишком долго. Попробуйте еще раз.');
            }
            throw error;
        } finally {
            clearTimeout(timeoutId);
        }
    }

    async function createSingleConfluenceReport(parentPageId, testId) {
        const response = await fetchWithTimeout('/api/confluence/report/', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                parentPageId,
                tests: [testId]
            })
        }, 300000);
        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(extractReportCreationErrorMessage(errorText));
        }
        return response.json();
    }

    async function createReleaseReportPage(parentPageId, testIds) {
        const response = await fetchWithTimeout('/api/confluence/report/release-page/', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                parentPageId,
                testUuids: testIds
            })
        }, 180000);
        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(extractReportCreationErrorMessage(errorText, 'Не удалось создать страницу отчета по НТ релиза'));
        }
        return response.json();
    }

    function extractSingleGeneratedReport(payload) {
        const reports = Array.isArray(payload?.reports) ? payload.reports : [];
        return reports[0] ?? null;
    }

    async function submitLuckyReports(event) {
        event.preventDefault();
        if (!luckyReportsCreateButton || luckyReportsCreateButton.disabled) {
            return;
        }

        const parentPageId = (luckyReportsParentPageIdInput?.value ?? '').trim();
        const selectedTests = getSelectedLuckyTests();
        const shouldCreateReleasePage = Boolean(luckyReportsCreateReleasePageCheckbox?.checked);
        reportGenerationResults = [
            ...(shouldCreateReleasePage ? [{
                order: 1,
                testId: RELEASE_REPORT_RESULT_ID,
                status: 'queued',
                message: '',
                pageTitle: 'Отчет по релизу',
                pageId: '',
                pageUrl: '',
                contextLabel: 'Страница отчета по НТ релиза'
            }] : []),
            ...selectedTests.map((test, index) => ({
                order: index + (shouldCreateReleasePage ? 2 : 1),
                testId: test?.id ?? '',
                status: 'queued',
                message: '',
                pageTitle: '',
                pageUrl: '',
                contextLabel: buildReportGenerationContextLabel(test)
            }))
        ];
        renderGenerationResults();

        isReportCreationInProgress = true;
        updateSubmitState();
        luckyReportsCreateButton.textContent = 'Создание…';
        showToast(
                selectedTests.length > 1
                    ? `Начинаем генерацию ${selectedTests.length} отчетов в Confluence…`
                    : 'Начинаем генерацию отчета в Confluence…',
                'info',
                6000);

        let successCount = 0;
        let errorCount = 0;
        let targetParentPageId = parentPageId;

        try {
            if (shouldCreateReleasePage) {
                try {
                    updateReportGenerationResult(RELEASE_REPORT_RESULT_ID, {
                        status: 'running',
                        message: ''
                    });
                    showToast('Создаем страницу отчета по НТ релиза…', 'info', 6000);
                    const releasePage = await createReleaseReportPage(
                            parentPageId,
                            selectedTests.map(test => test?.id).filter(Boolean));
                    if (!releasePage?.pageId) {
                        throw new Error('Confluence не вернул информацию о созданной странице отчета по НТ релиза');
                    }
                    targetParentPageId = releasePage.pageId;
                    updateReportGenerationResult(RELEASE_REPORT_RESULT_ID, {
                        status: 'success',
                        pageTitle: releasePage.pageTitle || 'Отчет по релизу',
                        pageId: releasePage.pageId || '',
                        pageUrl: releasePage.pageUrl || '',
                        message: ''
                    });
                    showToast('Страница отчета по НТ релиза создана.', 'success', 6000);
                } catch (error) {
                    const message = error?.message || 'Не удалось создать страницу отчета по НТ релиза';
                    reportGenerationResults = reportGenerationResults.map(result => ({
                        ...result,
                        status: 'error',
                        message: result.testId === RELEASE_REPORT_RESULT_ID
                                ? message
                                : 'Не запущен: не удалось создать страницу отчета по релизу'
                    }));
                    renderGenerationResults();
                    showToast(message, 'danger', 12000);
                    return;
                }
            }

            for (const test of selectedTests) {
                const testId = test?.id;
                if (!testId) {
                    continue;
                }
                updateReportGenerationResult(testId, {
                    status: 'running',
                    message: ''
                });
                try {
                    const payload = await createSingleConfluenceReport(
                            targetParentPageId,
                            testId
                    );
                    const report = extractSingleGeneratedReport(payload);
                    if (!report?.pageId) {
                        throw new Error('Confluence не вернул информацию о созданной странице');
                    }
                    updateReportGenerationResult(testId, {
                        status: 'success',
                        pageTitle: report.pageTitle || `Отчет ${reportGenerationResults.find(item => item.testId === testId)?.order ?? ''}`.trim(),
                        pageUrl: report.pageUrl || '',
                        message: ''
                    });
                    successCount += 1;
                } catch (error) {
                    updateReportGenerationResult(testId, {
                        status: 'error',
                        message: error?.message || 'Не удалось создать отчет'
                    });
                    errorCount += 1;
                    showToast(error?.message || 'Не удалось создать отчет', 'danger', 12000);
                }
            }
        } finally {
            isReportCreationInProgress = false;
            luckyReportsCreateButton.textContent = 'Создать отчеты по тестам';
            updateSubmitState();
        }

        if (errorCount === 0) {
            showToast(
                    successCount > 1
                        ? `Генерация завершена: создано ${successCount} отчетов.`
                        : 'Отчет успешно создан.',
                    'success',
                    6000);
            return;
        }

        if (successCount > 0) {
            showToast(
                    `Генерация завершена: создано ${successCount} из ${selectedTests.length} отчетов.`,
                    'warning',
                    12000);
            return;
        }

        showToast('Не удалось создать отчеты', 'danger', 12000);
    }

    async function handleLuckyReportsClick() {
        luckyReportsButton.disabled = true;
        luckyReportsButton.textContent = 'Подбираем…';
        try {
            await loadTests();
            const latestRelease = findLatestRelease(tests);
            if (!latestRelease) {
                showToast(
                        'Не повезло! К сожалению, не удалось найти тесты последнего релиза.',
                        'danger',
                        12000);
                return;
            }

            syncLatestReleaseSelection(latestRelease);
            if (latestReleaseTestIds.length === 0) {
                showToast(
                        'Не повезло! К сожалению, не удалось найти тесты последнего релиза.',
                        'danger',
                        12000);
                return;
            }

            reportGenerationResults = [];
            renderGenerationResults();
            openLuckyReportsModal();
        } catch (error) {
            showToast(error?.message || 'Не удалось подобрать тесты для генерации отчётов.', 'danger', 12000);
        } finally {
            luckyReportsButton.disabled = false;
            luckyReportsButton.textContent = 'Мне повезет!';
        }
    }

    luckyReportsButton.addEventListener('click', handleLuckyReportsClick);

    if (luckyReportsParentPageIdInput) {
        luckyReportsParentPageIdInput.addEventListener('change', () => {
            selectedReportParentPageId = (luckyReportsParentPageIdInput.value ?? '').trim();
            updateSubmitState();
        });
    }

    if (luckyReportsParentPageRefreshButton) {
        luckyReportsParentPageRefreshButton.addEventListener('click', () => {
            void loadConfluenceParentPages(true).finally(() => {
                if (luckyReportsModalElement?.classList.contains('show')) {
                    luckyReportsParentPageIdInput?.focus();
                }
            });
        });
    }

    if (luckyReportsCreateReleasePageCheckbox) {
        luckyReportsCreateReleasePageCheckbox.addEventListener('change', () => {
            updateSubmitState();
        });
    }

    if (luckyReportsModalForm) {
        luckyReportsModalForm.addEventListener('submit', submitLuckyReports);
    }

    if (luckyReportsModalElement) {
        luckyReportsModalElement.addEventListener('shown.bs.modal', () => {
            refreshLuckyReportsModalState();
            if (!confluenceParentPagesLoading) {
                luckyReportsParentPageIdInput?.focus();
            }
        });

        luckyReportsModalElement.addEventListener('hidden.bs.modal', () => {
            if (luckyReportsCreateButton && !isReportCreationInProgress) {
                luckyReportsCreateButton.textContent = 'Создать отчеты по тестам';
            }
            updateSubmitState();
        });

        luckyReportsModalElement.addEventListener('click', event => {
            const dismissTarget = event.target.closest('[data-bs-dismiss="modal"]');
            if (dismissTarget && !getLuckyReportsModalInstance()) {
                hideLuckyReportsModalFallback();
                return;
            }
            if (event.target === luckyReportsModalElement && !getLuckyReportsModalInstance()) {
                hideLuckyReportsModalFallback();
            }
        });
    }

    document.addEventListener('keydown', event => {
        if (event.key !== 'Escape') {
            return;
        }
        if (luckyReportsModalElement?.classList.contains('show') && !getLuckyReportsModalInstance()) {
            hideLuckyReportsModalFallback();
        }
    });
})();
