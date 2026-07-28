import type { RouteLocationRaw } from 'vue-router'

import type { MemoryEntrySummary } from '@/api/types'

/** Where an entry lives in the UI. Entries without a project scope have no page of their own. */
export function entryLocation(entry: {
  name: string
  projectScope?: string | null
  taskKey?: string | null
}): RouteLocationRaw | null {
  if (!entry.projectScope) {
    return null
  }
  return entry.taskKey
    ? { name: 'task-entry', params: { project: entry.projectScope, task: entry.taskKey, name: entry.name } }
    : { name: 'entry', params: { project: entry.projectScope, name: entry.name } }
}

export function projectLocation(projectScope: string): RouteLocationRaw {
  return { name: 'project', params: { project: projectScope } }
}

export function taskLocation(projectScope: string, taskKey: string): RouteLocationRaw {
  return { name: 'task', params: { project: projectScope, task: taskKey } }
}

export function graphLocation(projectScope: string, taskKey?: string | null): RouteLocationRaw {
  return taskKey
    ? { name: 'task-graph', params: { project: projectScope, task: taskKey } }
    : { name: 'project-graph', params: { project: projectScope } }
}

/** Path string for anchors rendered outside of Vue templates (markdown, SVG). */
export function entryHref(
  entry: Pick<MemoryEntrySummary, 'name' | 'projectScope' | 'taskKey'>,
): string | null {
  const project = entry.projectScope
  if (!project) {
    return null
  }
  const base = `/p/${encodeURIComponent(project)}`
  const task = entry.taskKey ? `/t/${encodeURIComponent(entry.taskKey)}` : ''
  return `${base}${task}/e/${encodeURIComponent(entry.name)}`
}
