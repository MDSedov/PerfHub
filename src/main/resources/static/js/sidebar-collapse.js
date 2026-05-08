(function () {
    const STORAGE_KEY = 'perfhub.sidebar.collapsed';
    const COLLAPSED_CLASS = 'sidebar-collapsed';
    const COLLAPSED_LOGO_SRC = '/img/perfhub-favicon-source.png';

    function getLinkText(link) {
        const textNode = Array.from(link.querySelectorAll('.sidebar-item > span'))
                .find(item => !item.classList.contains('menu-icon'));
        return textNode?.textContent?.trim() ?? link.textContent.trim();
    }

    function prepareLinks(sidebar) {
        sidebar.querySelectorAll('.sidebar-link, .sidebar-sublink, .sidebar-toggle').forEach(link => {
            const label = getLinkText(link);
            if (label) {
                link.setAttribute('title', label);
            }
        });
    }

    function prepareLogo(sidebar) {
        const logo = sidebar.querySelector('.brand-logo');
        if (!logo || logo.dataset.fullSrc) {
            return;
        }
        logo.dataset.fullSrc = logo.getAttribute('src') ?? '';
        logo.dataset.collapsedSrc = COLLAPSED_LOGO_SRC;
    }

    function setLogoState(sidebar, collapsed) {
        const logo = sidebar.querySelector('.brand-logo');
        if (!logo) {
            return;
        }
        const nextSrc = collapsed ? logo.dataset.collapsedSrc : logo.dataset.fullSrc;
        if (nextSrc) {
            logo.setAttribute('src', nextSrc);
        }
    }

    function setToggleState(button, collapsed) {
        const icon = button.querySelector('i');
        if (icon) {
            icon.className = collapsed ? 'bi bi-arrow-bar-right' : 'bi bi-arrow-bar-left';
        }
        const label = collapsed ? 'Развернуть меню' : 'Свернуть меню';
        button.setAttribute('aria-label', label);
        button.setAttribute('title', label);
        button.setAttribute('aria-expanded', (!collapsed).toString());
    }

    function applySidebarState(sidebar, button, collapsed) {
        document.documentElement.classList.toggle(COLLAPSED_CLASS, collapsed);
        document.body.classList.toggle(COLLAPSED_CLASS, collapsed);
        sidebar.classList.toggle(COLLAPSED_CLASS, collapsed);
        setLogoState(sidebar, collapsed);
        setToggleState(button, collapsed);
        try {
            localStorage.setItem(STORAGE_KEY, collapsed ? 'true' : 'false');
        } catch (error) {
            // The menu should still work when storage is unavailable.
        }
    }

    function createToggle(sidebar) {
        const existing = sidebar.querySelector('.sidebar-collapse-toggle');
        if (existing) {
            return existing;
        }
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'sidebar-collapse-toggle';
        button.innerHTML = '<i class="bi bi-arrow-bar-left"></i>';
        sidebar.appendChild(button);
        return button;
    }

    function initSidebarCollapse() {
        const sidebar = document.querySelector('.sidebar');
        if (!sidebar) {
            return;
        }

        prepareLinks(sidebar);
        prepareLogo(sidebar);
        const button = createToggle(sidebar);
        let collapsed = false;
        try {
            collapsed = localStorage.getItem(STORAGE_KEY) === 'true';
        } catch (error) {
            collapsed = false;
        }
        applySidebarState(sidebar, button, collapsed);

        button.addEventListener('click', () => {
            applySidebarState(sidebar, button, !document.body.classList.contains(COLLAPSED_CLASS));
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initSidebarCollapse);
    } else {
        initSidebarCollapse();
    }
})();
