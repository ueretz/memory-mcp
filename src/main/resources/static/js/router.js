export function initRouter({ home, project, task, entry, setup, graph }) {
    async function render() {
        try {
            const raw = location.hash.slice(1);
            if (raw === 'setup') {
                await setup();
                return;
            }
            const hash = raw.replace(/^\//, '');
            const segments = hash.split('/').filter(Boolean).map(decodeURIComponent);
            if (segments.length === 0) {
                await home();
            } else if (segments.length === 1) {
                await project(segments[0]);
            } else if (segments.length === 2) {
                if (segments[1] === 'graph') {
                    await graph(segments[0], null);
                } else {
                    await task(segments[0], segments[1]);
                }
            } else if (segments.length === 3 && segments[2] === 'graph') {
                await graph(segments[0], segments[1]);
            } else {
                await entry(segments[0], segments[1], segments[2]);
            }
        } catch (e) {
            const app = document.getElementById('app');
            app.innerHTML = '';
            const div = document.createElement('div');
            div.className = 'empty-state';
            div.textContent = `Failed to load this page: ${e.message}`;
            app.appendChild(div);
        }
    }

    window.addEventListener('hashchange', render);
    render();
    return render;
}

export function navigate(path) {
    location.hash = path;
}
