import { fetchGraph } from '../api.js';
import { renderBreadcrumb } from '../breadcrumb.js';
import { entryHref } from '../components.js';

const TYPES = ['USER', 'FEEDBACK', 'PROJECT', 'REFERENCE', 'LOCATION'];

export async function renderGraphView(projectScope, taskKey) {
    const crumbs = [
        { label: '📁 Projects', href: '#/' },
        { label: projectScope, href: `#/${encodeURIComponent(projectScope)}` },
    ];
    if (taskKey) {
        crumbs.push({
            label: taskKey,
            href: `#/${encodeURIComponent(projectScope)}/${encodeURIComponent(taskKey)}`,
        });
    }
    crumbs.push({ label: '🕸 Graph' });
    renderBreadcrumb(document.getElementById('breadcrumb'), crumbs);

    const app = document.getElementById('app');
    app.innerHTML = '';

    const title = document.createElement('h1');
    title.className = 'page-title';
    title.textContent = 'Graph';
    const subtitle = document.createElement('p');
    subtitle.className = 'page-subtitle';
    subtitle.textContent = taskKey
        ? `Links between ${taskKey}'s entries, derived from [[wiki-links]]. Drag nodes, scroll to zoom.`
        : "Links between this project's common entries, derived from [[wiki-links]]. Drag nodes, scroll to zoom.";
    app.append(title, subtitle);

    const toolbar = document.createElement('div');
    toolbar.className = 'graph-toolbar';

    const typeSelect = document.createElement('select');
    const allOption = document.createElement('option');
    allOption.value = '';
    allOption.textContent = 'All types';
    typeSelect.appendChild(allOption);
    for (const t of TYPES) {
        const opt = document.createElement('option');
        opt.value = t;
        opt.textContent = t;
        typeSelect.appendChild(opt);
    }
    toolbar.appendChild(typeSelect);

    const legend = document.createElement('div');
    legend.className = 'graph-legend';
    for (const t of TYPES) {
        const item = document.createElement('span');
        item.className = 'graph-legend-item';
        const dot = document.createElement('span');
        dot.className = `type-dot type-${t}`;
        const label = document.createElement('span');
        label.textContent = t;
        item.append(dot, label);
        legend.appendChild(item);
    }
    toolbar.appendChild(legend);
    app.appendChild(toolbar);

    const canvas = document.createElement('div');
    canvas.className = 'graph-canvas';
    app.appendChild(canvas);

    let stopSimulation = null;

    async function load() {
        if (stopSimulation) {
            stopSimulation();
            stopSimulation = null;
        }
        canvas.innerHTML = '';

        const data = await fetchGraph(projectScope, taskKey, typeSelect.value || undefined);
        if (data.nodes.length === 0) {
            const empty = document.createElement('div');
            empty.className = 'empty-state';
            empty.textContent = 'No entries to graph yet.';
            canvas.appendChild(empty);
            return;
        }
        stopSimulation = renderForceGraph(canvas, data, projectScope, taskKey);
    }

    typeSelect.addEventListener('change', load);
    await load();
}

const SVG_NS = 'http://www.w3.org/2000/svg';
const REPULSION = 12000;
const SPRING_LENGTH = 90;
const SPRING_STRENGTH = 0.04;
const CENTER_STRENGTH = 0.0006;
const DAMPING = 0.82;

function renderForceGraph(container, graphData, projectScope, taskKey) {
    const width = container.clientWidth || 800;
    const height = container.clientHeight || 560;

    const svg = document.createElementNS(SVG_NS, 'svg');
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    container.appendChild(svg);

    const viewport = document.createElementNS(SVG_NS, 'g');
    svg.appendChild(viewport);

    const edgesGroup = document.createElementNS(SVG_NS, 'g');
    const nodesGroup = document.createElementNS(SVG_NS, 'g');
    viewport.append(edgesGroup, nodesGroup);

    const nodes = graphData.nodes.map((n, i) => {
        const angle = (i / graphData.nodes.length) * Math.PI * 2;
        const radius = Math.min(width, height) * 0.3;
        return {
            ...n,
            x: width / 2 + Math.cos(angle) * radius,
            y: height / 2 + Math.sin(angle) * radius,
            vx: 0,
            vy: 0,
            fixed: false,
            didDrag: false,
        };
    });
    const byName = new Map(nodes.map((n) => [n.name, n]));
    const links = graphData.edges
        .map((e) => ({ source: byName.get(e.source), target: byName.get(e.target) }))
        .filter((l) => l.source && l.target);

    const linkEls = links.map(() => {
        const line = document.createElementNS(SVG_NS, 'line');
        line.setAttribute('class', 'graph-edge');
        edgesGroup.appendChild(line);
        return line;
    });

    const nodeEls = nodes.map((node) => {
        const g = document.createElementNS(SVG_NS, 'g');
        g.setAttribute('class', 'graph-node');

        const circle = document.createElementNS(SVG_NS, 'circle');
        circle.setAttribute('r', node.type === 'LOCATION' ? '5' : '8');
        circle.setAttribute('class', `graph-node-circle graph-fill-${node.type}`);
        g.appendChild(circle);

        const label = document.createElementNS(SVG_NS, 'text');
        label.setAttribute('class', 'graph-node-label');
        label.setAttribute('dy', '-12');
        label.textContent = node.name.length > 28 ? `${node.name.slice(0, 26)}…` : node.name;
        g.appendChild(label);

        const href = entryHref({ projectScope, taskKey, name: node.name, type: node.type });
        if (href) {
            g.style.cursor = 'pointer';
            g.addEventListener('click', () => {
                if (!node.didDrag) {
                    location.hash = href;
                }
            });
        }

        nodesGroup.appendChild(g);
        return g;
    });

    let scale = 1;
    let tx = 0;
    let ty = 0;

    function applyTransform() {
        viewport.setAttribute('transform', `translate(${tx},${ty}) scale(${scale})`);
    }

    function screenToWorld(clientX, clientY) {
        const rect = svg.getBoundingClientRect();
        return {
            x: (clientX - rect.left - tx) / scale,
            y: (clientY - rect.top - ty) / scale,
        };
    }

    svg.addEventListener(
        'wheel',
        (e) => {
            e.preventDefault();
            const rect = svg.getBoundingClientRect();
            const mx = e.clientX - rect.left;
            const my = e.clientY - rect.top;
            const prevScale = scale;
            scale = Math.min(3, Math.max(0.3, scale * (e.deltaY < 0 ? 1.1 : 0.9)));
            tx = mx - ((mx - tx) / prevScale) * scale;
            ty = my - ((my - ty) / prevScale) * scale;
            applyTransform();
        },
        { passive: false },
    );

    let panning = false;
    let panStart = null;
    svg.addEventListener('pointerdown', (e) => {
        if (e.target === svg) {
            panning = true;
            panStart = { x: e.clientX - tx, y: e.clientY - ty };
        }
    });
    const onPointerMove = (e) => {
        if (panning) {
            tx = e.clientX - panStart.x;
            ty = e.clientY - panStart.y;
            applyTransform();
        }
    };
    const onPointerUp = () => {
        panning = false;
    };
    window.addEventListener('pointermove', onPointerMove);
    window.addEventListener('pointerup', onPointerUp);

    nodeEls.forEach((g, i) => {
        const node = nodes[i];
        let dragging = false;
        g.addEventListener('pointerdown', (e) => {
            e.stopPropagation();
            dragging = true;
            node.fixed = true;
            node.didDrag = false;
            g.setPointerCapture(e.pointerId);
        });
        g.addEventListener('pointermove', (e) => {
            if (!dragging) return;
            node.didDrag = true;
            const p = screenToWorld(e.clientX, e.clientY);
            node.x = p.x;
            node.y = p.y;
            node.vx = 0;
            node.vy = 0;
        });
        g.addEventListener('pointerup', (e) => {
            dragging = false;
            g.releasePointerCapture(e.pointerId);
        });
    });

    let frameId = requestAnimationFrame(tick);

    function tick() {
        for (let i = 0; i < nodes.length; i++) {
            for (let j = i + 1; j < nodes.length; j++) {
                const a = nodes[i];
                const b = nodes[j];
                const dx = a.x - b.x;
                const dy = a.y - b.y;
                let dist2 = dx * dx + dy * dy;
                if (dist2 < 1) dist2 = 1;
                const dist = Math.sqrt(dist2);
                const force = REPULSION / dist2;
                const fx = (dx / dist) * force;
                const fy = (dy / dist) * force;
                if (!a.fixed) {
                    a.vx += fx;
                    a.vy += fy;
                }
                if (!b.fixed) {
                    b.vx -= fx;
                    b.vy -= fy;
                }
            }
        }

        for (const link of links) {
            const a = link.source;
            const b = link.target;
            const dx = b.x - a.x;
            const dy = b.y - a.y;
            const dist = Math.sqrt(dx * dx + dy * dy) || 1;
            const diff = (dist - SPRING_LENGTH) * SPRING_STRENGTH;
            const fx = (dx / dist) * diff;
            const fy = (dy / dist) * diff;
            if (!a.fixed) {
                a.vx += fx;
                a.vy += fy;
            }
            if (!b.fixed) {
                b.vx -= fx;
                b.vy -= fy;
            }
        }

        for (const node of nodes) {
            if (node.fixed) continue;
            node.vx += (width / 2 - node.x) * CENTER_STRENGTH;
            node.vy += (height / 2 - node.y) * CENTER_STRENGTH;
            node.vx *= DAMPING;
            node.vy *= DAMPING;
            node.x += node.vx;
            node.y += node.vy;
        }

        linkEls.forEach((line, i) => {
            const l = links[i];
            line.setAttribute('x1', l.source.x);
            line.setAttribute('y1', l.source.y);
            line.setAttribute('x2', l.target.x);
            line.setAttribute('y2', l.target.y);
        });
        nodeEls.forEach((g, i) => {
            const n = nodes[i];
            g.setAttribute('transform', `translate(${n.x},${n.y})`);
        });

        if (document.body.contains(container)) {
            frameId = requestAnimationFrame(tick);
        }
    }

    return () => {
        cancelAnimationFrame(frameId);
        window.removeEventListener('pointermove', onPointerMove);
        window.removeEventListener('pointerup', onPointerUp);
    };
}
