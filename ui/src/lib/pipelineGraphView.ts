import { MarkerType } from '@vue-flow/core'

import type { PipelineDetail, PipelineRunDetail, PipelineStepView } from '@/api/types'
import { WIRE_COLORS } from '@/lib/pipelineBoard'
import { isActiveStep, stageStatusFor, type StageStatus } from '@/lib/pipelineRuns'

/**
 * Shared edge builder for the read-only status graphs (PipelineView, PipelineRunView).
 *
 * A route-less pipeline (saved before branching existed) is executed by the engine as an implicit
 * orderIndex -> orderIndex+1 chain, so it is drawn that way; once any step has a route, only
 * explicit routes are drawn - never a mix (see PipelineRunService.resolveNextOrderIndex).
 * Data wires are intentionally not drawn: these views show control flow and status only.
 */
export const END_NODE_ID = 'end'

export interface ViewEdge {
  id: string
  source: string
  sourceHandle: string
  target: string
  targetHandle: string
  label?: string
  style: Record<string, string | number>
  markerEnd: { type: MarkerType; color: string; width: number; height: number }
  labelBgPadding: [number, number]
  labelBgBorderRadius: number
  class?: string
}

interface EdgeSpec {
  id: string
  source: PipelineStepView
  outcomeKey: string | null
  targetOrderIndex: number | null
}

function edgeSpecs(pipeline: PipelineDetail): EdgeSpec[] {
  const steps = pipeline.steps
  const hasAnyRoutes = steps.some((step) => step.routes.length > 0)
  if (!hasAnyRoutes) {
    return steps.map((step, index) => ({
      id: `${step.orderIndex}-implicit`,
      source: step,
      outcomeKey: null,
      targetOrderIndex: steps[index + 1]?.orderIndex ?? null,
    }))
  }
  return steps.flatMap((step) =>
    step.routes.map((route) => ({
      id: `${step.orderIndex}-${route.outcomeKey ?? 'default'}-${route.targetStepOrderIndex ?? END_NODE_ID}`,
      source: step,
      outcomeKey: route.outcomeKey,
      targetOrderIndex: route.targetStepOrderIndex,
    })),
  )
}

function edgeColor(spec: EdgeSpec): string {
  if (spec.source.contentType === 'CONDITION') {
    return spec.outcomeKey === 'true' ? WIRE_COLORS.trueBranch : WIRE_COLORS.falseBranch
  }
  return WIRE_COLORS.flow
}

/**
 * `taken` decides whether an edge was walked on a given run (run view) - when omitted (definition
 * view) every edge is drawn at full strength.
 */
export function buildViewEdges(pipeline: PipelineDetail, taken?: (spec: { sourceOrderIndex: number; targetOrderIndex: number | null }) => boolean): ViewEdge[] {
  return edgeSpecs(pipeline).map((spec) => {
    const walked = taken ? taken({ sourceOrderIndex: spec.source.orderIndex, targetOrderIndex: spec.targetOrderIndex }) : true
    const color = walked ? edgeColor(spec) : 'var(--color-border-strong)'
    const sourceHandle = spec.source.contentType === 'CONDITION' ? `flow-out-${spec.outcomeKey}` : 'flow-out'
    return {
      id: spec.id,
      source: String(spec.source.orderIndex),
      sourceHandle,
      target: spec.targetOrderIndex === null ? END_NODE_ID : String(spec.targetOrderIndex),
      targetHandle: 'flow-in',
      label: spec.outcomeKey ?? undefined,
      style: { stroke: color, strokeWidth: walked && taken ? 2.5 : 1.75, opacity: walked ? 1 : 0.7 },
      markerEnd: { type: MarkerType.ArrowClosed, color, width: 14, height: 14 },
      labelBgPadding: [5, 2],
      labelBgBorderRadius: 5,
      class: walked ? undefined : 'pl-edge-not-taken',
    }
  })
}

/**
 * For the run view: an edge counts as walked when its source step finished (DONE) and its target
 * was reached - i.e. the target step is no longer PENDING, or the target is the end node and the
 * run has nowhere left to go.
 */
export function takenEdgePredicate(run: PipelineRunDetail) {
  // (imports isActiveStep from pipelineRuns)
  const statusByOrderIndex = new Map(run.steps.map((s) => [s.orderIndex, s.status]))
  return ({ sourceOrderIndex, targetOrderIndex }: { sourceOrderIndex: number; targetOrderIndex: number | null }) => {
    const sourceStatus = statusByOrderIndex.get(sourceOrderIndex)
    if (sourceStatus !== 'DONE' && sourceStatus !== 'SKIPPED') return false
    if (targetOrderIndex === null) return run.currentStepOrderIndex === null
    const targetStatus = statusByOrderIndex.get(targetOrderIndex)
    return (targetStatus !== undefined && targetStatus !== 'PENDING') || isActiveStep(run, targetOrderIndex)
  }
}

const STEP_SPACING = 240

/** Legacy pipelines have every step at (0,0): spread them left-to-right instead of stacking. */
export function viewPositions(pipeline: PipelineDetail): Map<number, { x: number; y: number }> {
  const steps = pipeline.steps
  const allAtOrigin = steps.length > 0 && steps.every((s) => s.positionX === 0 && s.positionY === 0)
  const positions = new Map<number, { x: number; y: number }>()
  steps.forEach((step, index) => {
    positions.set(step.orderIndex, allAtOrigin ? { x: index * STEP_SPACING, y: 0 } : { x: step.positionX, y: step.positionY })
  })
  return positions
}

export function endNodePosition(pipeline: PipelineDetail, positions: Map<number, { x: number; y: number }>): { x: number; y: number } {
  const steps = pipeline.steps
  if (steps.length === 0) return { x: 0, y: 0 }
  const maxX = Math.max(...steps.map((s) => positions.get(s.orderIndex)!.x))
  const ys = steps.filter((s) => positions.get(s.orderIndex)!.x === maxX).map((s) => positions.get(s.orderIndex)!.y)
  return { x: maxX + 300, y: Math.min(...ys) }
}

export interface StatusNode {
  id: string
  type: 'pipelineStep'
  position: { x: number; y: number }
  class?: string
  data: {
    label: string
    status: StageStatus
    contentType?: PipelineStepView['contentType']
    current?: boolean
    note?: string | null
  }
}

/**
 * Nodes for the read-only status graph: neutral when no run is selected, otherwise each step is
 * colored by that run's progress and the step the run is standing on is marked current.
 */
export function buildStatusNodes(pipeline: PipelineDetail, run: PipelineRunDetail | null): StatusNode[] {
  const positions = viewPositions(pipeline)
  const runStepByOrderIndex = new Map(run?.steps.map((s) => [s.orderIndex, s]) ?? [])
  const finished = run !== null && run.currentStepOrderIndex === null && run.status === 'DONE'
  return [
    ...pipeline.steps.map((step) => ({
      id: String(step.orderIndex),
      type: 'pipelineStep' as const,
      position: positions.get(step.orderIndex)!,
      data: {
        label: `${step.orderIndex + 1}. ${step.title}`,
        status: run ? stageStatusFor(run, step.orderIndex) : ('neutral' as StageStatus),
        contentType: step.contentType,
        current: run !== null && run.status === 'RUNNING' && isActiveStep(run, step.orderIndex),
        note: runStepByOrderIndex.get(step.orderIndex)?.note ?? null,
      },
    })),
    {
      id: END_NODE_ID,
      type: 'pipelineStep' as const,
      position: endNodePosition(pipeline, positions),
      data: { label: 'Конец', status: (finished ? 'done' : 'end') as StageStatus },
    },
  ]
}
