/** Mirrors the records in ru.iuribabalin.memorymcp.dto — keep in sync with the backend. */

export const MEMORY_TYPES = ['USER', 'FEEDBACK', 'PROJECT', 'REFERENCE', 'LOCATION'] as const

export type MemoryType = (typeof MEMORY_TYPES)[number]

export type TaskStatus = 'ACTIVE' | 'DONE'

export type TaskSource = 'MANUAL' | 'JIRA'

export interface ProjectSummary {
  projectScope: string
  commonEntryCount: number
  taskCount: number
}

export interface TaskSummary {
  taskKey: string
  title: string | null
  source: TaskSource
  status: TaskStatus
  updatedAt: string
}

export interface MemoryEntrySummary {
  name: string
  type: MemoryType
  description: string
  projectScope: string | null
  taskKey: string | null
  filePath: string | null
  updatedAt: string
}

export interface MemoryEntryDetail extends MemoryEntrySummary {
  content: string
  createdAt: string
  linkedTo: MemoryEntrySummary[]
  linkedFrom: MemoryEntrySummary[]
  warnings: string[]
}

export interface GraphNode {
  name: string
  type: MemoryType
}

export interface GraphEdge {
  source: string
  target: string
}

export interface GraphResponse {
  nodes: GraphNode[]
  edges: GraphEdge[]
}

export interface SetupInfo {
  mcpAddCommand: string
  mcpServerUrl: string
  skillInstallPath: string
}
