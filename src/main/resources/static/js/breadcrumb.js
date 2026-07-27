export function renderBreadcrumb(container, items) {
    container.innerHTML = '';
    items.forEach((item, i) => {
        if (i > 0) {
            const sep = document.createElement('span');
            sep.className = 'breadcrumb-sep';
            sep.textContent = '›';
            container.appendChild(sep);
        }
        const el = document.createElement('span');
        el.className = 'breadcrumb-item';
        if (item.href && i < items.length - 1) {
            const a = document.createElement('a');
            a.href = item.href;
            a.textContent = item.label;
            el.appendChild(a);
        } else {
            el.textContent = item.label;
        }
        container.appendChild(el);
    });
}
