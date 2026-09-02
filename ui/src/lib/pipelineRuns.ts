import type { PipelineRunDetail, PipelineRunStatus, PipelineRunStepStatus } from '@/api/types'

export const RUN_STATUS_LABEL: Record<PipelineRunStatus, string> = {
  RUNNING: 'Выполняется',
  DONE: 'Готово',
  FAILED: 'Ошибка',
  ABORTED: 'Прервано',
}

export const STEP_STATUS_LABEL: Record<PipelineRunStepStatus, string> = {
  PENDING: 'Ожидает',
  RUNNING: 'Выполняется',
  DONE: 'Готово',
  FAILED: 'Ошибка',
  SKIPPED: 'Пропущен',
}

/** Status a step node should display for a run - the step being worked on counts as running. */
export type StageStatus = 'neutral' | 'pending' | 'running' | 'done' | 'failed' | 'skipped' | 'end'

export function isActiveStep(run: PipelineRunDetail, orderIndex: number): boolean {
  const active = run.activeStepOrderIndexes ?? (run.currentStepOrderIndex === null ? [] : [run.currentStepOrderIndex])
  return active.includes(orderIndex)
}

export function activeSteps(run: PipelineRunDetail) {
  const active = run.activeStepOrderIndexes ?? (run.currentStepOrderIndex === null ? [] : [run.currentStepOrderIndex])
  return active.map((i) => run.steps.find((s) => s.orderIndex === i)).filter((s): s is NonNullable<typeof s> => !!s)
}

export function stageStatusFor(run: PipelineRunDetail, orderIndex: number): StageStatus {
  const step = run.steps.find((s) => s.orderIndex === orderIndex)
  if (!step) return 'pending'
  if (run.status === 'RUNNING' && isActiveStep(run, orderIndex)) return 'running'
  switch (step.status) {
    case 'DONE':
      return 'done'
    case 'FAILED':
      return 'failed'
    case 'SKIPPED':
      return 'skipped'
    case 'RUNNING':
      return 'running'
    default:
      return 'pending'
  }
}

/** "1 мин 12 с" between two instants; open-ended runs measure up to now. */
export function formatDuration(startIso: string, endIso: string | null, now = Date.now()): string {
  const start = Date.parse(startIso)
  const end = endIso ? Date.parse(endIso) : now
  if (Number.isNaN(start) || Number.isNaN(end)) return ''
  const totalSeconds = Math.max(0, Math.round((end - start) / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  if (hours > 0) return `${hours} ч ${minutes} мин`
  if (minutes > 0) return `${minutes} мин ${seconds} с`
  return `${seconds} с`
}

export function formatClock(iso: string | null): string {
  if (!iso) return ''
  const time = Date.parse(iso)
  if (Number.isNaN(time)) return ''
  const date = new Date(time)
  const sameDay = date.toDateString() === new Date().toDateString()
  return sameDay
    ? date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleString(undefined, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
}

/** Parameters the run was started with, as name/value pairs (empty for malformed JSON). */
export function parseRunParameters(json: string | null): { name: string; value: string }[] {
  if (!json) return []
  try {
    const parsed = JSON.parse(json) as Record<string, unknown>
    return Object.entries(parsed).map(([name, value]) => ({ name, value: typeof value === 'string' ? value : JSON.stringify(value) }))
  } catch {
    return []
  }
}
