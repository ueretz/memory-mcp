async function getJson(url) {
    const res = await fetch(url);
    if (!res.ok) {
        throw new Error(`${url} -> HTTP ${res.status}`);
    }
    return res.json();
}

export function fetchProjects() {
    return getJson('/api/projects');
}

export function fetchTasks(projectScope) {
    return getJson(`/api/projects/${encodeURIComponent(projectScope)}/tasks`);
}

export function fetchEntries(projectScope, taskKey) {
    const params = new URLSearchParams({ projectScope });
    if (taskKey) params.set('taskKey', taskKey);
    return getJson(`/api/memory?${params}`);
}

export function fetchEntry(name) {
    return getJson(`/api/memory/${encodeURIComponent(name)}`);
}

export function searchEntries(query, type) {
    const params = new URLSearchParams({ q: query });
    if (type) params.set('type', type);
    return getJson(`/api/memory/search?${params}`);
}
