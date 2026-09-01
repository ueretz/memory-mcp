<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

import { VueFlow } from '@vue-flow/core'
import { computed, toRef } from 'vue'

import { fetchPipeline, fetchPipelineRun } from '@/api/client'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import PipelineStepNode from '@/components/PipelineStepNode.vue'
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

const STATUS_CLASS: Record<string, string> = {
  PENDING: 'pipeline-node pipeline-node-not-reached',
  RUNNING: 'pipeline-node pipeline-node-running',
  DONE: 'pipeline-node pipeline-node-done',
  FAILED: 'pipeline-node pipeline-node-failed',
  SKIPPED: 'pipeline-node pipeline-node-not-reached',
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
      const statusClass = runStep ? STATUS_CLASS[runStep.status] : 'pipeline-node pipeline-node-not-reached'
      const conditionClass = step.contentType === 'CONDITION' ? ' pipeline-node-condition' : ''
      return {
        id: String(step.orderIndex),
        type: 'pipelineStep',
        position: positions.get(step.orderIndex)!,
        class: (isCurrent(step.orderIndex) ? `${statusClass} pipeline-node-selected` : statusClass) + conditionClass,
        data: { label: `${step.orderIndex + 1}. ${step.title}${runStep?.note ? ` — ${runStep.note}` : ''}`, outputs: step.outputs, contentType: step.contentType },
      }
    }),
    {
      id: END_NODE_ID,
      type: 'pipelineStep',
      position: { x: maxX + 240, y: 0 },
      class: run.value.currentStepOrderIndex === null ? 'pipeline-node pipeline-node-end pipeline-node-done' : 'pipeline-node pipeline-node-end',
      data: { label: 'Конец рана', outputs: [], contentType: 'PROMPT' },
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
  // Data-link edges are drawn unconditionally regardless of whether the pipeline is route-less
  // (implicit chain) or has real routes - computed once here so both branches below can never
  // drift apart on this again.
  const dataLinkEdges = steps.flatMap((step) =>
    step.dataLinksOut.map((link) => ({
      id: `data-${link.token}`,
      source: String(step.orderIndex),
      sourceHandle: `output-${link.sourceOutputName}`,
      target: String(link.targetStepOrderIndex),
      targetHandle: 'data-in',
      class: 'pipeline-data-edge',
      style: { strokeDasharray: '4 4', stroke: '#10b981' },
    })),
  )
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
        sourceHandle: 'route',
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
      <VueFlow :nodes="flowNodes" :edges="flowEdges" :node-types="{ pipelineStep: PipelineStepNode }" :nodes-draggable="false" :edges-updatable="false" fit-view-on-init />
    </div>
  </div>
</template>
