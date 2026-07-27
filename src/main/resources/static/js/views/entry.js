import { fetchEntry } from '../api.js';
import { renderBreadcrumb } from '../breadcrumb.js';
import { linkChip, entryHref } from '../components.js';
import { renderMarkdown } from '../markdown.js';

export async function renderEntryView(projectScope, parentKey, entryName) {
    const parentLabel = parentKey === 'common' ? 'Common' : parentKey;
    const parentHref = parentKey === 'common'
        ? `#/${encodeURIComponent(projectScope)}`
        : `#/${encodeURIComponent(projectScope)}/${encodeURIComponent(parentKey)}`;

    renderBreadcrumb(document.getElementById('breadcrumb'), [
        { label: '📁 Projects', href: '#/' },
        { label: projectScope, href: `#/${encodeURIComponent(projectScope)}` },
        { label: parentLabel, href: parentHref },
        { label: entryName },
    ]);

    const app = document.getElementById('app');
    app.innerHTML = '';

    const entry = await fetchEntry(entryName);

    const page = document.createElement('div');
    page.className = 'entry-page';

    const header = document.createElement('div');
    header.className = 'entry-header';
    const h1 = document.createElement('h1');
    h1.textContent = entry.name;
    const type = document.createElement('span');
    type.className = `type-text-${entry.type}`;
    type.textContent = entry.type;
    header.append(h1, type);

    const metaParts = [entry.projectScope, entry.taskKey].filter(Boolean).join(' / ') || 'no scope';
    const meta = document.createElement('div');
    meta.className = 'entry-meta';
    meta.textContent = entry.filePath
        ? `${metaParts} · 📄 ${entry.filePath} · updated ${new Date(entry.updatedAt).toLocaleString()}`
        : `${metaParts} · updated ${new Date(entry.updatedAt).toLocaleString()}`;

    const description = document.createElement('div');
    description.className = 'entry-description';
    description.textContent = entry.description;

    const linkMap = new Map((entry.linkedTo || []).map((linked) => [linked.name, entryHref(linked)]));
    const content = document.createElement('div');
    content.className = 'markdown-body';
    content.innerHTML = renderMarkdown(entry.content, (name) => linkMap.get(name) || null);

    page.append(header, meta, description, content);
    page.appendChild(linksSection('Links to', entry.linkedTo));
    page.appendChild(linksSection('Linked from', entry.linkedFrom));

    app.appendChild(page);
}

function linksSection(title, entries) {
    const section = document.createElement('div');
    section.className = 'links-section';
    if (!entries || entries.length === 0) {
        return section;
    }
    const h3 = document.createElement('h3');
    h3.textContent = title;
    section.appendChild(h3);
    for (const linked of entries) {
        section.appendChild(linkChip(linked));
    }
    return section;
}
