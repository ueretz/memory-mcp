/** Mirrors the records in ru.iuribabalin.memorymcp.dto — keep in sync with the backend. */

export const MEMORY_TYPES = ['USER', 'FEEDBACK', 'PROJECT', 'REFERENCE', 'LOCATION', 'REPORT'] as const

export type MemoryType = (typeof MEMORY_TYPES)[number]

export type TaskStatus = 'ACTIVE' | 'DONE' | 'CANCELLED'

export type TaskSource = 'MANUAL' | 'JIRA'

export type AgentTaskType = 'ANALYSIS' | 'IMPLEMENTATION' | 'TESTING' | 'REVIEW' | 'REPORTING'

export type AgentTaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED'

/** The MD entry a task or subtask links as its spec - the description lives there, not inline. */
export interface SpecSummary {
  name: string
  description: string
  updatedAt: string
}

/** Share of a task's subtasks that are DONE, computed server-side - never entered by hand. */
export interface TaskProgress {
  done: number
  total: number
  percent: number
}

export interface AgentTaskSummary {
  id: number
  title: string
  type: AgentTaskType
  status: AgentTaskStatus
  description: string | null
  spec: SpecSummary | null
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
  progress: TaskProgress
  specEntryName: string | null
  updatedAt: string
}

export interface TaskDetail {
  taskKey: string
  title: string | null
  source: TaskSource
  status: TaskStatus
  spec: SpecSummary | null
  progress: TaskProgress
  createdAt: string
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
/**
 * `VARIABLE` is historical: V24 moved variables onto the pipeline's input plate, so no step can be
 * one any more. It stays in the union because run steps recorded before then still report it.
 */
export type PipelineStepContentType = 'PROMPT' | 'MD_FILE' | 'CONDITION' | 'VARIABLE' | 'PARALLEL' | 'JOIN' | 'LOOP' | 'WATCHER'
export type PipelineWatchSource = 'GITLAB_MR' | 'COMMAND'
export type PipelineWatchAspect = 'PIPELINE' | 'REVIEW'
export type PipelineConditionOperator = 'EQUALS' | 'GREATER_THAN' | 'LESS_THAN' | 'GREATER_OR_EQUAL' | 'LESS_OR_EQUAL'
export type PipelineConditionMode = 'VALUE' | 'PROMPT'
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
  conditionMode: PipelineConditionMode
  maxIterations: number | null
  watchSource: PipelineWatchSource | null
  watchAspect: PipelineWatchAspect | null
  checkIntervalSeconds: number | null
  watchTimeoutSeconds: number | null
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
  lastRunId: number | null
  lastRunStatus: PipelineRunStatus | null
  lastRunStartedAt: string | null
}

export interface PipelineParameterLinkView {
  id: number
  token: string
  parameterName: string
  targetStepOrderIndex: number | null
  targetStepTitle: string | null
  /** Set instead of the step target when the parameter helps build a variable. */
  targetVariableName: string | null
}

export interface PipelineUpsertParameterLink {
  token: string
  parameterName: string
  targetStepIndex: number | null
  targetVariableName?: string | null
}

/** A named constant on the input plate. Not a step - see PipelineVariable in the backend. */
export interface PipelineVariableView {
  id: number
  name: string
  value: string | null
  orderIndex: number
}

export interface PipelineVariableLinkView {
  id: number
  token: string
  variableName: string
  targetStepOrderIndex: number | null
  targetStepTitle: string | null
  /** Set instead of the step target when one variable's value is built from another's. */
  targetVariableName: string | null
}

export interface PipelineUpsertVariable {
  name: string
  value: string | null
}

export interface PipelineUpsertVariableLink {
  token: string
  variableName: string
  targetStepIndex: number | null
  targetVariableName: string | null
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
  variables: PipelineVariableView[]
  variableLinks: PipelineVariableLinkView[]
  /** Null only while a pipeline has no steps yet. */
  startStepOrderIndex: number | null
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
  conditionMode: PipelineConditionMode
  maxIterations: number | null
  watchSource: PipelineWatchSource | null
  watchAspect: PipelineWatchAspect | null
  checkIntervalSeconds: number | null
  watchTimeoutSeconds: number | null
}

export interface PipelineUpsertRequest {
  slug: string
  name: string
  description: string | null
  projectScope: string | null
  parameters: PipelineUpsertParameter[]
  steps: PipelineUpsertStep[]
  parameterLinks: PipelineUpsertParameterLink[]
  variables: PipelineUpsertVariable[]
  variableLinks: PipelineUpsertVariableLink[]
  /** Which step a run begins at. The board always states it; null leaves it to the backend. */
  startStepIndex: number | null
}

export interface PipelineExportEnvelope {
  formatVersion: number
  pipeline: PipelineUpsertRequest
  warnings: string[]
}

export interface PipelineImportResponse {
  pipeline: PipelineDetail
  warnings: string[]
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
  executionCount: number
  /** WATCHER steps only. watchExpired is derived server-side on every read. */
  watchCheckCount: number | null
  watchLastCheckedAt: string | null
  watchLastState: string | null
  watchDeadlineAt: string | null
  watchExpired: boolean | null
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
