import { fetchEntries, fetchTasks } from '../api.js';
import { renderBreadcrumb } from '../breadcrumb.js';
import { typeDot, statusBadge } from '../components.js';

export async function renderProjectView(projectScope) {
    renderBreadcrumb(document.getElementById('breadcrumb'), [
        { label: '📁 Projects', href: '#/' },
        { label: projectScope },
    ]);

    const app = document.getElementById('app');
    app.innerHTML = '';

    const title = document.createElement('h1');
    title.className = 'page-title';
    title.textContent = projectScope;
    app.appendChild(title);

    const [common, tasks] = await Promise.all([
        fetchEntries(projectScope, null),
        fetchTasks(projectScope),
    ]);

    app.appendChild(buildSection('📄 Common', common, (entry) => {
        const href = `#/${encodeURIComponent(projectScope)}/common/${encodeURIComponent(entry.name)}`;
        return entryRow('📄', entry.name, entry.description, entry.updatedAt, href, typeDot(entry.type));
    }));

    app.appendChild(buildSection('🗂️ Tasks', tasks, (task) => {
        const href = `#/${encodeURIComponent(projectScope)}/${encodeURIComponent(task.taskKey)}`;
        const label = task.title ? `${task.taskKey} — ${task.title}` : task.taskKey;
        return entryRow('🗂️', label, null, task.updatedAt, href, statusBadge(task.status));
    }));
}

function buildSection(title, items, rowBuilder) {
    const section = document.createElement('div');
    section.className = 'section';

    const heading = document.createElement('div');
    heading.className = 'section-title';
    heading.textContent = title;
    section.appendChild(heading);

    if (items.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'empty-state';
        empty.textContent = 'Nothing here yet.';
        section.appendChild(empty);
        return section;
    }

    const list = document.createElement('div');
    list.className = 'row-list';
    for (const item of items) {
        list.appendChild(rowBuilder(item));
    }
    section.appendChild(list);
    return section;
}

function entryRow(icon, label, description, updatedAt, href, badgeEl) {
    const row = document.createElement('a');
    row.className = 'row-item';
    row.href = href;

    const iconEl = document.createElement('span');
    iconEl.className = 'row-icon';
    iconEl.textContent = icon;

    const main = document.createElement('div');
    main.className = 'row-main';

    const nameEl = document.createElement('div');
    nameEl.className = 'row-name';
    const labelSpan = document.createElement('span');
    labelSpan.textContent = label;
    nameEl.append(badgeEl, labelSpan);
    main.appendChild(nameEl);

    if (description) {
        const descEl = document.createElement('div');
        descEl.className = 'row-desc';
        descEl.textContent = description;
        main.appendChild(descEl);
    }

    const meta = document.createElement('div');
    meta.className = 'row-meta';
    meta.textContent = new Date(updatedAt).toLocaleDateString();

    row.append(iconEl, main, meta);
    return row;
}
