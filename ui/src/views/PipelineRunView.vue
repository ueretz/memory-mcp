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
import { END_NODE_ID, buildViewEdges, endNodePosition, takenEdgePredicate, viewPositions } from '@/lib/pipelineGraphView'

const props = defineProps<{ project: string; slug: string; runId: string }>()
const slug = toRef(props, 'slug')
const runId = toRef(props, 'runId')

const { data: run, error, loading } = useAsyncData(() => fetchPipelineRun(Number(runId.value)), [runId])
const { data: pipeline, error: pipelineError, loading: pipelineLoading } = useAsyncData(() => fetchPipeline(slug.value), [slug])

const title = computed(() => (run.value ? `Запуск #${run.value.id} — ${run.value.pipelineSlug}` : `Запуск #${runId.value}`))

// Run-step status -> PipelineMiniStepNode status (GitLab-CI-style circles).
const MINI_STATUS: Record<string, string> = {
  PENDING: 'pending',
  RUNNING: 'running',
  DONE: 'done',
  FAILED: 'failed',
  SKIPPED: 'skipped',
}

const flowNodes = computed(() => {
  if (!pipeline.value || !run.value) return []
  const runStepByOrderIndex = new Map(run.value.steps.map((s) => [s.orderIndex, s]))
  const positions = viewPositions(pipeline.value)
  const isCurrent = (orderIndex: number) => run.value!.currentStepOrderIndex === orderIndex
  return [
    ...pipeline.value.steps.map((step) => {
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
      position: endNodePosition(pipeline.value, positions),
      data: {
        label: 'Конец',
        status: run.value.currentStepOrderIndex === null ? 'done' : 'end',
      },
    },
  ]
})

// The path the run actually walked is drawn at full strength; every other transition is dimmed.
const flowEdges = computed(() => (pipeline.value && run.value ? buildViewEdges(pipeline.value, takenEdgePredicate(run.value)) : []))
</script>

<template>
  <div>
    <PageHeader eyebrow="Pipeline run" :title="title" />

    <ErrorState v-if="error || pipelineError" :message="(error || pipelineError)!" />
    <SkeletonRows v-else-if="loading || pipelineLoading" :rows="3" />

    <div v-else-if="run && pipeline" class="h-[420px] overflow-hidden rounded-xl border border-border bg-elevated">
      <VueFlow :nodes="flowNodes" :edges="flowEdges" :node-types="{ pipelineStep: PipelineMiniStepNode }" :nodes-draggable="false" :nodes-connectable="false" :edges-updatable="false" :zoom-on-scroll="false" fit-view-on-init class="pl-flow-readonly" />
    </div>
  </div>
</template>
