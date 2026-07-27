import { fetchProjects } from '../api.js';
import { renderBreadcrumb } from '../breadcrumb.js';

export async function renderProjectsView() {
    renderBreadcrumb(document.getElementById('breadcrumb'), [
        { label: '📁 Projects' },
    ]);

    const app = document.getElementById('app');
    app.innerHTML = '';

    const title = document.createElement('h1');
    title.className = 'page-title';
    title.textContent = 'Projects';
    const subtitle = document.createElement('p');
    subtitle.className = 'page-subtitle';
    subtitle.textContent = 'Every project memory-mcp has stored context for.';
    app.append(title, subtitle);

    const projects = await fetchProjects();
    if (projects.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'empty-state';
        empty.textContent = 'No projects yet - save a memory entry to get started.';
        app.appendChild(empty);
        return;
    }

    const grid = document.createElement('div');
    grid.className = 'card-grid';
    for (const project of projects) {
        const card = document.createElement('a');
        card.className = 'project-card';
        card.href = `#/${encodeURIComponent(project.projectScope)}`;

        const icon = document.createElement('div');
        icon.className = 'project-card-icon';
        icon.textContent = '📁';

        const name = document.createElement('div');
        name.className = 'project-card-name';
        name.textContent = project.projectScope;

        const stats = document.createElement('div');
        stats.className = 'project-card-stats';
        stats.textContent = `${project.commonEntryCount} common · ${project.taskCount} task(s)`;

        card.append(icon, name, stats);
        grid.appendChild(card);
    }
    app.appendChild(grid);
}
