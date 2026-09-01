<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

import { VueFlow } from '@vue-flow/core'
import { computed, toRef } from 'vue'

import { fetchPipeline, fetchPipelineRun } from '@/api/client'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'

const props = defineProps<{ project: string; slug: string; runId: string }>()
const slug = toRef(props, 'slug')
const runId = toRef(props, 'runId')

const { data: run, error, loading } = useAsyncData(() => fetchPipelineRun(Number(runId.value)), [runId])
const { data: pipeline, loading: pipelineLoading } = useAsyncData(() => fetchPipeline(slug.value), [slug])

const title = computed(() => (run.value ? `Запуск #${run.value.id} — ${run.value.pipelineSlug}` : `Запуск #${runId.value}`))

const END_NODE_ID = 'end'

const STATUS_CLASS: Record<string, string> = {
  PENDING: 'pipeline-node pipeline-node-not-reached',
  RUNNING: 'pipeline-node pipeline-node-running',
  DONE: 'pipeline-node pipeline-node-done',
  FAILED: 'pipeline-node pipeline-node-failed',
  SKIPPED: 'pipeline-node pipeline-node-not-reached',
}

const flowNodes = computed(() => {
  if (!pipeline.value || !run.value) return []
  const runStepByOrderIndex = new Map(run.value.steps.map((s) => [s.orderIndex, s]))
  const steps = pipeline.value.steps
  const maxX = steps.length > 0 ? Math.max(...steps.map((s) => s.positionX)) : 0
  const isCurrent = (orderIndex: number) => run.value!.currentStepOrderIndex === orderIndex
  return [
    ...steps.map((step) => {
      const runStep = runStepByOrderIndex.get(step.orderIndex)
      const statusClass = runStep ? STATUS_CLASS[runStep.status] : 'pipeline-node pipeline-node-not-reached'
      return {
        id: String(step.orderIndex),
        position: { x: step.positionX, y: step.positionY },
        label: `${step.orderIndex + 1}. ${step.title}${runStep?.note ? ` — ${runStep.note}` : ''}`,
        class: isCurrent(step.orderIndex) ? `${statusClass} pipeline-node-selected` : statusClass,
      }
    }),
    {
      id: END_NODE_ID,
      position: { x: maxX + 240, y: 0 },
      label: 'Конец рана',
      class: run.value.currentStepOrderIndex === null ? 'pipeline-node pipeline-node-end pipeline-node-done' : 'pipeline-node pipeline-node-end',
    },
  ]
})

const flowEdges = computed(() => {
  if (!pipeline.value) return []
  return pipeline.value.steps.flatMap((step) =>
    step.routes.map((route) => ({
      id: `${step.orderIndex}-${route.outcomeKey ?? 'default'}-${route.targetStepOrderIndex ?? END_NODE_ID}`,
      source: String(step.orderIndex),
      target: route.targetStepOrderIndex === null ? END_NODE_ID : String(route.targetStepOrderIndex),
      label: route.outcomeKey ?? '(по умолчанию)',
    })),
  )
})
</script>

<template>
  <div>
    <PageHeader eyebrow="Pipeline run" :title="title" />

    <ErrorState v-if="error" :message="error" />
    <SkeletonRows v-else-if="loading || pipelineLoading" :rows="3" />

    <div v-else-if="run && pipeline" class="h-[420px] overflow-hidden rounded-xl border border-border bg-elevated">
      <VueFlow :nodes="flowNodes" :edges="flowEdges" :nodes-draggable="false" :edges-updatable="false" fit-view-on-init />
    </div>
  </div>
</template>
