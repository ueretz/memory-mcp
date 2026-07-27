import { renderBreadcrumb } from '../breadcrumb.js';

async function fetchSetupInfo() {
    const res = await fetch('/api/setup');
    if (!res.ok) {
        throw new Error(`/api/setup -> HTTP ${res.status}`);
    }
    return res.json();
}

export async function renderSetupView() {
    renderBreadcrumb(document.getElementById('breadcrumb'), [
        { label: '⚙️ Setup' },
    ]);

    const app = document.getElementById('app');
    app.innerHTML = '';

    const title = document.createElement('h1');
    title.className = 'page-title';
    title.textContent = 'Connect memory-mcp to Claude Code';
    const subtitle = document.createElement('p');
    subtitle.className = 'page-subtitle';
    subtitle.textContent = 'Three steps: start Postgres, register the MCP server, install the skill that teaches Claude when to use it.';
    app.append(title, subtitle);

    const info = await fetchSetupInfo();

    app.appendChild(step(
        '1. Start Postgres',
        'The MCP server and this dashboard both read/write the same database - it needs to be running.',
        'docker compose up -d',
    ));

    app.appendChild(step(
        '2. Register the MCP server with Claude Code',
        `Run this once. It registers memory-mcp at user scope, so it's available in every project, launched via ${info.javaExecutable}.`,
        info.mcpAddCommand,
    ));

    app.appendChild(skillStep(info.skillInstallPath));
}

function step(title, description, code) {
    const section = document.createElement('div');
    section.className = 'section';

    const heading = document.createElement('div');
    heading.className = 'section-title';
    heading.textContent = title;

    const desc = document.createElement('p');
    desc.style.color = 'var(--text-muted)';
    desc.style.fontSize = '13px';
    desc.style.margin = '0 0 10px';
    desc.textContent = description;

    section.append(heading, desc, codeBlock(code));
    return section;
}

function skillStep(installPath) {
    const section = document.createElement('div');
    section.className = 'section';

    const heading = document.createElement('div');
    heading.className = 'section-title';
    heading.textContent = '3. Install the skill';

    const desc = document.createElement('p');
    desc.style.color = 'var(--text-muted)';
    desc.style.fontSize = '13px';
    desc.style.margin = '0 0 10px';
    desc.textContent = 'The skill teaches Claude when to save/search memory, how to detect the project automatically, and to always ask before scoping work to a task. Download it and place it at:';

    const pathCode = codeBlock(installPath);

    const downloadLink = document.createElement('a');
    downloadLink.href = '/api/setup/skill';
    downloadLink.download = 'SKILL.md';
    downloadLink.className = 'btn';
    downloadLink.textContent = '⬇ Download SKILL.md';

    section.append(heading, desc, pathCode, downloadLink);
    return section;
}

function codeBlock(code) {
    const wrapper = document.createElement('div');
    wrapper.className = 'code-block';

    const pre = document.createElement('pre');
    pre.textContent = code;

    const copyBtn = document.createElement('button');
    copyBtn.className = 'copy-btn';
    copyBtn.textContent = 'Copy';
    copyBtn.addEventListener('click', async () => {
        await navigator.clipboard.writeText(code);
        copyBtn.textContent = 'Copied!';
        setTimeout(() => { copyBtn.textContent = 'Copy'; }, 1500);
    });

    wrapper.append(pre, copyBtn);
    return wrapper;
}
