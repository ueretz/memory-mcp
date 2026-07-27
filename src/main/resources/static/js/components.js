export function typeDot(type) {
    const el = document.createElement('span');
    el.className = `type-dot type-${type}`;
    return el;
}

export function statusBadge(status) {
    const el = document.createElement('span');
    el.className = `badge badge-status-${status}`;
    el.textContent = status;
    return el;
}

/** Canonical hash path for a memory entry summary, or null if it has no project scope. */
export function entryHref(summary) {
    if (!summary.projectScope) {
        return null;
    }
    const parent = summary.taskKey || 'common';
    return `#/${encodeURIComponent(summary.projectScope)}/${encodeURIComponent(parent)}/${encodeURIComponent(summary.name)}`;
}

export function linkChip(summary) {
    const href = entryHref(summary);
    const chip = document.createElement(href ? 'a' : 'span');
    chip.className = 'link-chip';
    if (href) {
        chip.href = href;
    }
    chip.appendChild(typeDot(summary.type));
    const label = document.createElement('span');
    label.textContent = summary.name;
    chip.appendChild(label);
    return chip;
}
