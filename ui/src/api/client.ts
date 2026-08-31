import { bumpDataVersion } from '@/lib/dataVersion'

import type {
  AgentTaskSummary,
  FolderSummary,
  GraphResponse,
  MemoryEntryDetail,
  MemoryEntrySummary,
  MemoryType,
  PipelineAssetSummary,
  PipelineDetail,
  PipelineRunDetail,
  PipelineRunSummary,
  PipelineSummary,
  PipelineUpsertRequest,
  ProjectSummary,
  SetupInfo,
  SettingSummary,
  StatsOverview,
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

async function deleteRequest(path: string): Promise<void> {
  let response: Response
  try {
    response = await fetch(path, { method: 'DELETE' })
  } catch {
    throw new ApiError(0, 'Cannot reach the memory-mcp server')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    const message = (body as { error?: string } | null)?.error ?? `HTTP ${response.status}`
    throw new ApiError(response.status, message)
  }
  bumpDataVersion()
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'Cannot reach the memory-mcp server')
  }
  if (!response.ok) {
    const errorBody = await response.json().catch(() => null)
    const message = (errorBody as { error?: string } | null)?.error ?? `HTTP ${response.status}`
    throw new ApiError(response.status, message)
  }
  bumpDataVersion()
  return response.json() as Promise<T>
}

async function putJson<T>(path: string, body: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'Cannot reach the memory-mcp server')
  }
  if (!response.ok) {
    const errorBody = await response.json().catch(() => null)
    const message = (errorBody as { error?: string } | null)?.error ?? `HTTP ${response.status}`
    throw new ApiError(response.status, message)
  }
  bumpDataVersion()
  return response.json() as Promise<T>
}

export function fetchProjects(): Promise<ProjectSummary[]> {
  return getJson('/api/projects')
}

export function fetchTasks(projectScope: string): Promise<TaskSummary[]> {
  return getJson(`/api/projects/${encodeURIComponent(projectScope)}/tasks`)
}

export function fetchAgentTasks(projectScope: string, taskKey: string): Promise<AgentTaskSummary[]> {
  return getJson(`/api/projects/${encodeURIComponent(projectScope)}/tasks/${encodeURIComponent(taskKey)}/agent-tasks`)
}

export function fetchEntries(
  projectScope: string,
  taskKey?: string | null,
  type?: MemoryType | null,
  folder?: string | null,
): Promise<MemoryEntrySummary[]> {
  return getJson('/api/memory', {
    projectScope,
    taskKey: taskKey ?? undefined,
    type: type ?? undefined,
    folder: folder ?? undefined,
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

export function fetchStats(
  projectScope?: string | null,
  taskKey?: string | null,
  days = 30,
): Promise<StatsOverview> {
  return getJson('/api/stats/overview', {
    projectScope: projectScope ?? undefined,
    taskKey: taskKey ?? undefined,
    days,
  })
}

export function fetchFolders(
  projectScope: string,
  taskKey?: string | null,
  parentFolder?: string | null,
): Promise<FolderSummary[]> {
  return getJson('/api/folders', {
    projectScope,
    taskKey: taskKey ?? undefined,
    parent: parentFolder ?? undefined,
  })
}

export function fetchFolder(name: string): Promise<FolderSummary> {
  return getJson(`/api/folders/${encodeURIComponent(name)}`)
}

export function deleteEntry(name: string): Promise<void> {
  return deleteRequest(`/api/memory/${encodeURIComponent(name)}`)
}

export function deleteFolder(name: string): Promise<void> {
  return deleteRequest(`/api/folders/${encodeURIComponent(name)}`)
}

export function deleteTask(projectScope: string, taskKey: string): Promise<void> {
  return deleteRequest(`/api/projects/${encodeURIComponent(projectScope)}/tasks/${encodeURIComponent(taskKey)}`)
}

export function deleteProject(projectScope: string): Promise<void> {
  return deleteRequest(`/api/projects/${encodeURIComponent(projectScope)}`)
}

export function fetchSettings(): Promise<SettingSummary[]> {
  return getJson('/api/settings')
}

export function updateSetting(key: string, value: string): Promise<SettingSummary> {
  return putJson(`/api/settings/${encodeURIComponent(key)}`, { value })
}

export function fetchPipelines(projectScope: string): Promise<PipelineSummary[]> {
  return getJson('/api/pipelines', { projectScope })
}

export function fetchPipeline(slug: string): Promise<PipelineDetail> {
  return getJson(`/api/pipelines/${encodeURIComponent(slug)}`)
}

export function createPipeline(request: PipelineUpsertRequest): Promise<PipelineDetail> {
  return postJson('/api/pipelines', request)
}

export function updatePipeline(slug: string, request: PipelineUpsertRequest): Promise<PipelineDetail> {
  return putJson(`/api/pipelines/${encodeURIComponent(slug)}`, request)
}

export function deletePipeline(slug: string): Promise<void> {
  return deleteRequest(`/api/pipelines/${encodeURIComponent(slug)}`)
}

export async function uploadPipelineAsset(file: File): Promise<PipelineAssetSummary> {
  const formData = new FormData()
  formData.append('file', file)
  let response: Response
  try {
    response = await fetch('/api/pipeline-assets', { method: 'POST', body: formData })
  } catch {
    throw new ApiError(0, 'Cannot reach the memory-mcp server')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    const message = (body as { error?: string } | null)?.error ?? `HTTP ${response.status}`
    throw new ApiError(response.status, message)
  }
  return response.json() as Promise<PipelineAssetSummary>
}

export function fetchPipelineRuns(slug: string): Promise<PipelineRunSummary[]> {
  return getJson(`/api/pipelines/${encodeURIComponent(slug)}/runs`)
}

export function fetchPipelineRun(id: number): Promise<PipelineRunDetail> {
  return getJson(`/api/pipeline-runs/${id}`)
}
