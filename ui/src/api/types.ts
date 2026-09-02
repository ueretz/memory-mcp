/** Mirrors the records in ru.iuribabalin.memorymcp.dto — keep in sync with the backend. */

export const MEMORY_TYPES = ['USER', 'FEEDBACK', 'PROJECT', 'REFERENCE', 'LOCATION', 'REPORT'] as const

export type MemoryType = (typeof MEMORY_TYPES)[number]

export type TaskStatus = 'ACTIVE' | 'DONE'

export type TaskSource = 'MANUAL' | 'JIRA'

export type AgentTaskType = 'ANALYSIS' | 'IMPLEMENTATION' | 'TESTING' | 'REVIEW' | 'REPORTING'

export type AgentTaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED'

export interface AgentTaskSummary {
  id: number
  title: string
  type: AgentTaskType
  status: AgentTaskStatus
  description: string | null
  updatedAt: string
  dependsOnId: number | null
}

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
  folder?: string | null
  createdBy: string | null
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

export interface SkillInfo {
  id: string
  title: string
  description: string
  installPath: string
  downloadUrl: string
}

export interface SetupInfo {
  mcpAddCommand: string
  mcpServerUrl: string
  skills: SkillInfo[]
}

export interface StatsTotals {
  totalEntries: number
  totalEvents: number
}

export interface DailyActivity {
  day: string
  count: number
}

export interface TypeBreakdown {
  type: MemoryType
  count: number
}

export interface TopEntry {
  name: string
  type: MemoryType
  description: string
  projectScope: string | null
  taskKey: string | null
  accessCount: number
}

export interface StatsOverview {
  totals: StatsTotals
  activityByDay: DailyActivity[]
  byType: TypeBreakdown[]
  topEntries: TopEntry[]
}

export interface FolderSummary {
  name: string
  description: string
  projectScope: string
  taskKey: string | null
  parentFolder: string | null
  createdBy: string | null
  updatedAt: string
}

export interface SettingSummary {
  key: string
  value: string
  updatedAt: string
}

export type PipelineParameterType = 'STRING' | 'NUMBER' | 'BOOLEAN'
export type PipelineStepContentType = 'PROMPT' | 'MD_FILE' | 'CONDITION' | 'VARIABLE' | 'PARALLEL' | 'JOIN'
export type PipelineConditionOperator = 'EQUALS' | 'GREATER_THAN' | 'LESS_THAN' | 'GREATER_OR_EQUAL' | 'LESS_OR_EQUAL'
export type PipelineRunStatus = 'RUNNING' | 'DONE' | 'FAILED' | 'ABORTED'
export type PipelineRunStepStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'

export interface PipelineAssetSummary {
  id: number
  filename: string
  contentType: string
  sizeBytes: number
  createdAt: string
}

export interface PipelineParameterView {
  id: number
  name: string
  label: string
  type: PipelineParameterType
  required: boolean
  defaultValue: string | null
  orderIndex: number
}

export interface PipelineRouteView {
  outcomeKey: string | null
  targetStepOrderIndex: number | null
}

export interface PipelineUpsertRoute {
  outcomeKey: string | null
  targetStepIndex: number | null
}

export interface PipelineOutputView {
  id: number
  name: string
}

export interface PipelineUpsertOutput {
  name: string
}

export interface PipelineDataLinkView {
  id: number
  token: string
  sourceOutputName: string
  targetStepOrderIndex: number | null
  targetStepTitle: string | null
}

export interface PipelineUpsertDataLink {
  token: string
  sourceOutputName: string
  targetStepIndex: number | null
}

export interface PipelineStepView {
  id: number
  orderIndex: number
  title: string
  contentType: PipelineStepContentType
  promptText: string | null
  assetId: number | null
  referenceAssetId: number | null
  positionX: number
  positionY: number
  routes: PipelineRouteView[]
  outputs: PipelineOutputView[]
  dataLinksOut: PipelineDataLinkView[]
  conditionOperator: PipelineConditionOperator | null
  conditionValue: string | null
}

export interface PipelineSummary {
  id: number
  slug: string
  name: string
  description: string | null
  projectScope: string | null
  parameterCount: number
  stepCount: number
  createdBy: string | null
  updatedAt: string
}

export interface PipelineParameterLinkView {
  id: number
  token: string
  parameterName: string
  targetStepOrderIndex: number | null
  targetStepTitle: string | null
}

export interface PipelineUpsertParameterLink {
  token: string
  parameterName: string
  targetStepIndex: number | null
}

export interface PipelineDetail {
  id: number
  slug: string
  name: string
  description: string | null
  projectScope: string | null
  parameters: PipelineParameterView[]
  steps: PipelineStepView[]
  parameterLinks: PipelineParameterLinkView[]
  createdBy: string | null
  createdAt: string
  updatedAt: string
}

export interface PipelineUpsertParameter {
  name: string
  label: string
  type: PipelineParameterType
  required: boolean
  defaultValue: string | null
}

export interface PipelineUpsertStep {
  title: string
  contentType: PipelineStepContentType
  promptText: string | null
  assetId: number | null
  referenceAssetId: number | null
  positionX: number
  positionY: number
  routes: PipelineUpsertRoute[]
  outputs: PipelineUpsertOutput[]
  dataLinksOut: PipelineUpsertDataLink[]
  conditionOperator: PipelineConditionOperator | null
  conditionValue: string | null
}

export interface PipelineUpsertRequest {
  slug: string
  name: string
  description: string | null
  projectScope: string | null
  parameters: PipelineUpsertParameter[]
  steps: PipelineUpsertStep[]
  parameterLinks: PipelineUpsertParameterLink[]
}

export interface PipelineRunSummary {
  id: number
  pipelineId: number
  pipelineSlug: string
  status: PipelineRunStatus
  startedAt: string
  finishedAt: string | null
  startedBy: string | null
  currentStepOrderIndex: number | null
  currentStepTitle: string | null
  doneStepCount: number
  totalStepCount: number
  activeSteps: { orderIndex: number; title: string }[]
}

export interface PipelineRunStepView {
  id: number
  orderIndex: number
  title: string
  contentType: PipelineStepContentType
  status: PipelineRunStepStatus
  note: string | null
  startedAt: string | null
  finishedAt: string | null
  resolvedInstructionText: string | null
}

export interface PipelineRunDetail {
  id: number
  pipelineId: number
  pipelineSlug: string
  status: PipelineRunStatus
  parametersJson: string | null
  startedAt: string
  finishedAt: string | null
  startedBy: string | null
  currentStepOrderIndex: number | null
  steps: PipelineRunStepView[]
  /** Every step being worked on right now - several while parallel branches are in flight. */
  activeStepOrderIndexes: number[]
}
