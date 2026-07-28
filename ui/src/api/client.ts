import type {
  GraphResponse,
  MemoryEntryDetail,
  MemoryEntrySummary,
  MemoryType,
  ProjectSummary,
  SetupInfo,
  TaskSummary,
} from './types'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function getJson<T>(path: string, params?: Record<string, string | number | undefined>): Promise<T> {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params ?? {})) {
    if (value !== undefined && value !== '') {
      query.set(key, String(value))
    }
  }
  const url = query.size > 0 ? `${path}?${query}` : path

  let response: Response
  try {
    response = await fetch(url, { headers: { Accept: 'application/json' } })
  } catch {
    throw new ApiError(0, 'Cannot reach the memory-mcp server')
  }

  if (!response.ok) {
    // The backend reports domain errors as {"error": "..."}.
    const body = await response.json().catch(() => null)
    const message = (body as { error?: string } | null)?.error ?? `HTTP ${response.status}`
    throw new ApiError(response.status, message)
  }
  return response.json() as Promise<T>
}

export function fetchProjects(): Promise<ProjectSummary[]> {
  return getJson('/api/projects')
}

export function fetchTasks(projectScope: string): Promise<TaskSummary[]> {
  return getJson(`/api/projects/${encodeURIComponent(projectScope)}/tasks`)
}

export function fetchEntries(
  projectScope: string,
  taskKey?: string | null,
  type?: MemoryType | null,
): Promise<MemoryEntrySummary[]> {
  return getJson('/api/memory', {
    projectScope,
    taskKey: taskKey ?? undefined,
    type: type ?? undefined,
    limit: 200,
  })
}

export function fetchEntry(name: string): Promise<MemoryEntryDetail> {
  return getJson(`/api/memory/${encodeURIComponent(name)}`)
}

export function searchEntries(
  query: string,
  type?: MemoryType | null,
  projectScope?: string | null,
): Promise<MemoryEntrySummary[]> {
  return getJson('/api/memory/search', {
    q: query,
    type: type ?? undefined,
    projectScope: projectScope ?? undefined,
    limit: 20,
  })
}

export function fetchGraph(
  projectScope: string,
  taskKey?: string | null,
  type?: MemoryType | null,
): Promise<GraphResponse> {
  return getJson('/api/memory/graph', {
    projectScope,
    taskKey: taskKey ?? undefined,
    type: type ?? undefined,
  })
}

export function fetchSetupInfo(): Promise<SetupInfo> {
  return getJson('/api/setup')
}
