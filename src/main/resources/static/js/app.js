import { searchEntries } from './api.js';
import { initRouter } from './router.js';
import { renderBreadcrumb } from './breadcrumb.js';
import { typeDot, entryHref } from './components.js';
import { renderProjectsView } from './views/projects.js';
import { renderProjectView } from './views/project.js';
import { renderTaskView } from './views/task.js';
import { renderEntryView } from './views/entry.js';
import { renderSetupView } from './views/setup.js';

const searchEl = document.getElementById('search');
const typeFilterEl = document.getElementById('typeFilter');

const renderRoute = initRouter({
    home: renderProjectsView,
    project: renderProjectView,
    task: renderTaskView,
    entry: renderEntryView,
    setup: renderSetupView,
});

async function runSearch() {
    const query = searchEl.value.trim();
    if (!query) {
        renderRoute();
        return;
    }

    renderBreadcrumb(document.getElementById('breadcrumb'), [
        { label: '📁 Projects', href: '#/' },
        { label: `Search: "${query}"` },
    ]);

    const app = document.getElementById('app');
    app.innerHTML = '';

    const results = await searchEntries(query, typeFilterEl.value || undefined);

    const header = document.createElement('div');
    header.className = 'search-results-header';
    header.textContent = `${results.length} result(s)`;
    app.appendChild(header);

    if (results.length === 0) {
        return;
    }

    const list = document.createElement('div');
    list.className = 'row-list';
    for (const entry of results) {
        const href = entryHref(entry);
        const row = document.createElement(href ? 'a' : 'div');
        row.className = 'row-item';
        if (href) {
            row.href = href;
        }

        const icon = document.createElement('span');
        icon.className = 'row-icon';
        icon.textContent = '📄';

        const main = document.createElement('div');
        main.className = 'row-main';
        const nameEl = document.createElement('div');
        nameEl.className = 'row-name';
        const labelSpan = document.createElement('span');
        labelSpan.textContent = `${entry.name}${entry.projectScope ? ` — ${entry.projectScope}` : ''}${entry.taskKey ? `/${entry.taskKey}` : ''}`;
        nameEl.append(typeDot(entry.type), labelSpan);
        const descEl = document.createElement('div');
        descEl.className = 'row-desc';
        descEl.textContent = entry.description;
        main.append(nameEl, descEl);

        row.append(icon, main);
        list.appendChild(row);
    }
    app.appendChild(list);
}

function debounce(fn, ms) {
    let timer;
    return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), ms);
    };
}

searchEl.addEventListener('input', debounce(runSearch, 250));
typeFilterEl.addEventListener('change', runSearch);
