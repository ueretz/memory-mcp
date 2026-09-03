import type { PipelineDetail, PipelineRunDetail, PipelineStepView } from '@/api/types'
import { WIRE_COLORS } from '@/lib/pipelineBoard'
import { isActiveStep, stageStatusFor, takenEdgePredicate, type StageStatus } from '@/lib/pipelineRuns'

/**
 * GitLab-CI-style layered layout for the read-only pipeline preview: every step lands in a
 * column ("stage") by its longest path from the start, branches stack vertically inside the
 * column, and wires are drawn left-to-right between columns. Board positions are ignored on
 * purpose - the preview is for reading a run, not for editing the graph.
 */

export const STAGE_CARD_W = 228
export const STAGE_CARD_H = 52
export const STAGE_HUB = 36
const COL_GAP = 84
const ROW_GAP = 18
const PAD_X = 24
const PAD_TOP = 22
const HEADER_H = 26
export const END_NODE_ID = 'end'

export interface StageNode {
  id: string
  kind: 'step' | 'end'
  step?: PipelineStepView
  /** PARALLEL / JOIN render as small hubs rather than cards. */
  hub: boolean
  col: number
  row: number
  x: number
  y: number
  w: number
  h: number
  status: StageStatus
  current: boolean
  note: string | null
}

export interface StageEdge {
  id: string
  from: string
  to: string
  label: string | null
  color: string
  taken: boolean
  path: string
  labelX: number
  labelY: number
}

export interface StageLayout {
  nodes: StageNode[]
  edges: StageEdge[]
  columns: number
  width: number
  height: number
}

interface RawEdge {
  from: number
  to: number | null
  label: string | null
  fromStep: PipelineStepView
}

function rawEdges(pipeline: PipelineDetail): RawEdge[] {
  const steps = pipeline.steps
  const hasAnyRoutes = steps.some((s) => s.routes.length > 0)
  if (!hasAnyRoutes) {
    return steps.map((step, i) => ({
      from: step.orderIndex,
      to: steps[i + 1]?.orderIndex ?? null,
      label: null,
      fromStep: step,
    }))
  }
  return steps.flatMap((step) =>
    step.routes.map((route) => ({ from: step.orderIndex, to: route.targetStepOrderIndex, label: route.outcomeKey, fromStep: step })),
  )
}

export function layoutStages(pipeline: PipelineDetail, run: PipelineRunDetail | null): StageLayout {
  const steps = pipeline.steps
  const edges = rawEdges(pipeline)
  const byIndex = new Map(steps.map((s) => [s.orderIndex, s]))

  // Longest path from any source = column. Predecessor lists over wired step->step edges.
  const preds = new Map<number, number[]>()
  steps.forEach((s) => preds.set(s.orderIndex, []))
  edges.forEach((e) => {
    if (e.to !== null && byIndex.has(e.to)) preds.get(e.to)!.push(e.from)
  })
  const depth = new Map<number, number>()
  const visiting = new Set<number>()
  function depthOf(i: number): number {
    const known = depth.get(i)
    if (known !== undefined) return known
    if (visiting.has(i)) return 0 // cycles are rejected at save time; guard anyway
    visiting.add(i)
    const p = preds.get(i) ?? []
    const d = p.length === 0 ? 0 : 1 + Math.max(...p.map(depthOf))
    visiting.delete(i)
    depth.set(i, d)
    return d
  }
  steps.forEach((s) => depthOf(s.orderIndex))
  const maxDepth = steps.length ? Math.max(...steps.map((s) => depth.get(s.orderIndex)!)) : -1
  const endCol = maxDepth + 1

  // Rows: per column, order by the average row of predecessors (keeps branches from crossing).
  const columns = new Map<number, number[]>()
  steps.forEach((s) => {
    const c = depth.get(s.orderIndex)!
    if (!columns.has(c)) columns.set(c, [])
    columns.get(c)!.push(s.orderIndex)
  })
  const rowOf = new Map<number, number>()
  for (let c = 0; c <= maxDepth; c++) {
    const members = columns.get(c) ?? []
    const keyed = members.map((i) => {
      const p = (preds.get(i) ?? []).map((x) => rowOf.get(x)).filter((r): r is number => r !== undefined)
      return { i, key: p.length ? p.reduce((a, b) => a + b, 0) / p.length : i }
    })
    keyed.sort((a, b) => a.key - b.key || a.i - b.i)
    keyed.forEach((k, r) => rowOf.set(k.i, r))
  }
  const endRow = (() => {
    const sources = edges.filter((e) => e.to === null).map((e) => rowOf.get(e.from) ?? 0)
    return sources.length ? Math.round(sources.reduce((a, b) => a + b, 0) / sources.length) : 0
  })()

  const colX = (c: number) => PAD_X + c * (STAGE_CARD_W + COL_GAP)
  const rowY = (r: number) => PAD_TOP + HEADER_H + r * (STAGE_CARD_H + ROW_GAP)

  const finished = run !== null && run.currentStepOrderIndex === null && run.status === 'DONE'
  const runStepByIndex = new Map(run?.steps.map((s) => [s.orderIndex, s]) ?? [])

  const nodes: StageNode[] = steps.map((step) => {
    const hub = step.contentType === 'PARALLEL' || step.contentType === 'JOIN'
    const c = depth.get(step.orderIndex)!
    const r = rowOf.get(step.orderIndex)!
    const w = hub ? STAGE_HUB : STAGE_CARD_W
    const h = hub ? STAGE_HUB : STAGE_CARD_H
    return {
      id: String(step.orderIndex),
      kind: 'step',
      step,
      hub,
      col: c,
      row: r,
      x: colX(c) + (hub ? (STAGE_CARD_W - STAGE_HUB) / 2 : 0),
      y: rowY(r) + (hub ? (STAGE_CARD_H - STAGE_HUB) / 2 : 0),
      w,
      h,
      status: run ? stageStatusFor(run, step.orderIndex) : 'neutral',
      current: run !== null && run.status === 'RUNNING' && isActiveStep(run, step.orderIndex),
      note: runStepByIndex.get(step.orderIndex)?.note ?? null,
    }
  })
  nodes.push({
    id: END_NODE_ID,
    kind: 'end',
    hub: false,
    col: endCol,
    row: endRow,
    x: colX(endCol),
    y: rowY(endRow) + (STAGE_CARD_H - STAGE_HUB) / 2,
    w: 96,
    h: STAGE_HUB,
    status: finished ? 'done' : 'end',
    current: false,
    note: null,
  })
  const nodeById = new Map(nodes.map((n) => [n.id, n]))

  const taken = run ? takenEdgePredicate(run) : null
  // Several labelled routes may leave one step (or even share a target): stack their labels
  // instead of painting them on top of each other.
  const labelSlot = new Map<number, number>()
  const stageEdges: StageEdge[] = edges.flatMap((e) => {
    const from = nodeById.get(String(e.from))
    const to = nodeById.get(e.to === null ? END_NODE_ID : String(e.to))
    if (!from || !to) return []
    const walked = taken ? taken({ sourceOrderIndex: e.from, targetOrderIndex: e.to }) : true
    const isCondition = e.fromStep.contentType === 'CONDITION'
    const color = !walked
      ? 'var(--color-border-strong)'
      : isCondition && e.label === 'true' ? WIRE_COLORS.trueBranch
        : isCondition && e.label === 'false' ? WIRE_COLORS.falseBranch
          : WIRE_COLORS.flow
    const x1 = from.x + from.w
    const y1 = from.y + from.h / 2
    const x2 = to.x
    const y2 = to.y + to.h / 2
    const dx = Math.max(24, (x2 - x1) / 2)
    // Point on the cubic at t=0.4 (closer to the source, clear of the target node), then
    // nudge each further label from the same source down by one row of label height.
    const t = 0.4
    const bez = (a: number, b: number, c: number, d: number) =>
      (1 - t) ** 3 * a + 3 * (1 - t) ** 2 * t * b + 3 * (1 - t) * t ** 2 * c + t ** 3 * d
    const slot = e.label ? labelSlot.get(e.from) ?? 0 : 0
    if (e.label) labelSlot.set(e.from, slot + 1)
    return [{
      id: `${e.from}-${e.label ?? 'default'}-${e.to ?? END_NODE_ID}`,
      from: from.id,
      to: to.id,
      label: e.label,
      color,
      taken: walked,
      path: `M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`,
      labelX: bez(x1, x1 + dx, x2 - dx, x2),
      labelY: bez(y1, y1, y2, y2) + slot * 20,
    }]
  })

  const maxRows = Math.max(1, ...Array.from(columns.values()).map((m) => m.length), endRow + 1)
  return {
    nodes,
    edges: stageEdges,
    columns: endCol + 1,
    width: colX(endCol) + 96 + PAD_X,
    height: rowY(maxRows - 1) + STAGE_CARD_H + PAD_TOP,
  }
}

export function columnLeft(col: number): number {
  return PAD_X + col * (STAGE_CARD_W + COL_GAP)
}

export const STAGE_HEADER_TOP = PAD_TOP
