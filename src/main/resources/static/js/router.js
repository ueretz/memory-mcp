export function initRouter({ home, project, task, entry, setup }) {
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
                await task(segments[0], segments[1]);
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
