import { fetchEntries, fetchTasks } from '../api.js';
import { renderBreadcrumb } from '../breadcrumb.js';
import { typeDot, statusBadge } from '../components.js';

export async function renderTaskView(projectScope, taskKey) {
    renderBreadcrumb(document.getElementById('breadcrumb'), [
        { label: '📁 Projects', href: '#/' },
        { label: projectScope, href: `#/${encodeURIComponent(projectScope)}` },
        { label: taskKey },
    ]);

    const app = document.getElementById('app');
    app.innerHTML = '';

    const [entries, tasks] = await Promise.all([
        fetchEntries(projectScope, taskKey),
        fetchTasks(projectScope),
    ]);
    const task = tasks.find((t) => t.taskKey === taskKey);

    const header = document.createElement('div');
    header.style.display = 'flex';
    header.style.alignItems = 'center';
    header.style.justifyContent = 'space-between';
    header.style.gap = '10px';
    const titleGroup = document.createElement('div');
    titleGroup.style.display = 'flex';
    titleGroup.style.alignItems = 'center';
    titleGroup.style.gap = '10px';
    const title = document.createElement('h1');
    title.className = 'page-title';
    title.textContent = taskKey;
    titleGroup.appendChild(title);
    if (task) {
        titleGroup.appendChild(statusBadge(task.status));
    }
    const graphLink = document.createElement('a');
    graphLink.className = 'btn';
    graphLink.href = `#/${encodeURIComponent(projectScope)}/${encodeURIComponent(taskKey)}/graph`;
    graphLink.textContent = '🕸 Graph';
    header.append(titleGroup, graphLink);
    app.appendChild(header);

    const subtitle = document.createElement('p');
    subtitle.className = 'page-subtitle';
    subtitle.textContent = task && task.title ? task.title : 'Task working notes';
    app.appendChild(subtitle);

    if (entries.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'empty-state';
        empty.textContent = 'No entries saved for this task yet.';
        app.appendChild(empty);
        return;
    }

    const list = document.createElement('div');
    list.className = 'row-list';
    for (const entry of entries) {
        const row = document.createElement('a');
        row.className = 'row-item';
        row.href = `#/${encodeURIComponent(projectScope)}/${encodeURIComponent(taskKey)}/${encodeURIComponent(entry.name)}`;

        const icon = document.createElement('span');
        icon.className = 'row-icon';
        icon.textContent = '📄';

        const main = document.createElement('div');
        main.className = 'row-main';
        const nameEl = document.createElement('div');
        nameEl.className = 'row-name';
        const labelSpan = document.createElement('span');
        labelSpan.textContent = entry.name;
        nameEl.append(typeDot(entry.type), labelSpan);
        const descEl = document.createElement('div');
        descEl.className = 'row-desc';
        descEl.textContent = entry.description;
        main.append(nameEl, descEl);

        const meta = document.createElement('div');
        meta.className = 'row-meta';
        meta.textContent = new Date(entry.updatedAt).toLocaleDateString();

        row.append(icon, main, meta);
        list.appendChild(row);
    }
    app.appendChild(list);
}
