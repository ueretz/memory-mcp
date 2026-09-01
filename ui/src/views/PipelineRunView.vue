<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

import { VueFlow } from '@vue-flow/core'
import { computed, toRef } from 'vue'

import { fetchPipeline, fetchPipelineRun } from '@/api/client'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import PipelineMiniStepNode from '@/components/PipelineMiniStepNode.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'

const props = defineProps<{ project: string; slug: string; runId: string }>()
const slug = toRef(props, 'slug')
const runId = toRef(props, 'runId')

const { data: run, error, loading } = useAsyncData(() => fetchPipelineRun(Number(runId.value)), [runId])
const { data: pipeline, error: pipelineError, loading: pipelineLoading } = useAsyncData(() => fetchPipeline(slug.value), [slug])

const title = computed(() => (run.value ? `Запуск #${run.value.id} — ${run.value.pipelineSlug}` : `Запуск #${runId.value}`))

const END_NODE_ID = 'end'
const STEP_SPACING = 220

// Run-step status -> PipelineMiniStepNode status (GitLab-CI-style circles).
const MINI_STATUS: Record<string, string> = {
  PENDING: 'pending',
  RUNNING: 'running',
  DONE: 'done',
  FAILED: 'failed',
  SKIPPED: 'skipped',
}

// Pipelines created before the canvas builder (or saved without dragging any node) have every
// step at (0,0) - same convention as PipelineBuilderView's applyLegacyAutoLayoutIfNeeded. Spread
// them out left-to-right instead of stacking every node on top of the others.
const stepPositions = computed(() => {
  const steps = pipeline.value?.steps ?? []
  const allAtOrigin = steps.length > 0 && steps.every((s) => s.positionX === 0 && s.positionY === 0)
  const positions = new Map<number, { x: number; y: number }>()
  steps.forEach((step, index) => {
    positions.set(step.orderIndex, allAtOrigin ? { x: index * STEP_SPACING, y: 0 } : { x: step.positionX, y: step.positionY })
  })
  return positions
})

const flowNodes = computed(() => {
  if (!pipeline.value || !run.value) return []
  const runStepByOrderIndex = new Map(run.value.steps.map((s) => [s.orderIndex, s]))
  const steps = pipeline.value.steps
  const positions = stepPositions.value
  const maxX = steps.length > 0 ? Math.max(...steps.map((s) => positions.get(s.orderIndex)!.x)) : 0
  const isCurrent = (orderIndex: number) => run.value!.currentStepOrderIndex === orderIndex
  return [
    ...steps.map((step) => {
      const runStep = runStepByOrderIndex.get(step.orderIndex)
      return {
        id: String(step.orderIndex),
        type: 'pipelineStep',
        position: positions.get(step.orderIndex)!,
        data: {
          label: `${step.orderIndex + 1}. ${step.title}`,
          status: runStep ? MINI_STATUS[runStep.status] : 'pending',
          contentType: step.contentType,
          current: isCurrent(step.orderIndex),
          note: runStep?.note ?? null,
        },
      }
    }),
    {
      id: END_NODE_ID,
      type: 'pipelineStep',
      position: { x: maxX + 240, y: 0 },
      data: {
        label: 'Конец',
        status: run.value.currentStepOrderIndex === null ? 'done' : 'end',
      },
    },
  ]
})

// A route-less pipeline (linear/legacy - the backward-compatible default) has an empty `routes`
// array on every step. The backend treats that as an implicit orderIndex -> orderIndex+1 chain
// (see PipelineRunService.resolveNextOrderIndex's allRoutes.isEmpty() branch); mirror that here so
// the graph isn't drawn as disconnected nodes. Only one or the other - never mix implicit chaining
// with real routes.
const flowEdges = computed(() => {
  if (!pipeline.value) return []
  const steps = pipeline.value.steps
  const hasAnyRoutes = steps.some((step) => step.routes.length > 0)
  // Data-link edges are intentionally NOT drawn here: the compact GitLab-style view shows only
  // control flow (statuses); data wiring is inspected on the board.
  const dataLinkEdges: never[] = []
  if (!hasAnyRoutes) {
    return [
      ...steps.map((step, index) => {
        const nextStep = steps[index + 1]
        const target = nextStep ? String(nextStep.orderIndex) : END_NODE_ID
        return {
          id: `${step.orderIndex}-implicit-${target}`,
          source: String(step.orderIndex),
          sourceHandle: 'route',
          target,
          targetHandle: 'data-in',
          label: undefined as string | undefined,
        }
      }),
      ...dataLinkEdges,
    ]
  }
  return [
    ...steps.flatMap((step) =>
      step.routes.map((route) => ({
        id: `${step.orderIndex}-${route.outcomeKey ?? 'default'}-${route.targetStepOrderIndex ?? END_NODE_ID}`,
        source: String(step.orderIndex),
        sourceHandle: step.contentType === 'CONDITION' ? `route-${route.outcomeKey}` : 'route',
        target: route.targetStepOrderIndex === null ? END_NODE_ID : String(route.targetStepOrderIndex),
        targetHandle: 'data-in',
        label: route.outcomeKey ?? '(по умолчанию)' as string | undefined,
      })),
    ),
    ...dataLinkEdges,
  ]
})
</script>

<template>
  <div>
    <PageHeader eyebrow="Pipeline run" :title="title" />

    <ErrorState v-if="error || pipelineError" :message="(error || pipelineError)!" />
    <SkeletonRows v-else-if="loading || pipelineLoading" :rows="3" />

    <div v-else-if="run && pipeline" class="h-[420px] overflow-hidden rounded-xl border border-border bg-elevated">
      <VueFlow :nodes="flowNodes" :edges="flowEdges" :node-types="{ pipelineStep: PipelineMiniStepNode }" :nodes-draggable="false" :edges-updatable="false" fit-view-on-init />
    </div>
  </div>
</template>
