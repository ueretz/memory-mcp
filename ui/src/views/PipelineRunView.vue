<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

import { VueFlow } from '@vue-flow/core'
import { computed, onBeforeUnmount, onMounted, ref, toRef } from 'vue'

import { fetchPipeline, fetchPipelineRun } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import PipelineMiniStepNode from '@/components/PipelineMiniStepNode.vue'
import PipelineRunStatusBadge from '@/components/PipelineRunStatusBadge.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { usePolling } from '@/composables/usePolling'
import { BLOCK_KIND_BY_TYPE } from '@/lib/pipelineBoard'
import { buildStatusNodes, buildViewEdges, takenEdgePredicate } from '@/lib/pipelineGraphView'
import { STEP_STATUS_LABEL, activeSteps, formatClock, formatDuration, parseRunParameters, stageStatusFor } from '@/lib/pipelineRuns'

const props = defineProps<{ project: string; slug: string; runId: string }>()
const slug = toRef(props, 'slug')
const runId = toRef(props, 'runId')

const { data: run, error, loading, reload } = useAsyncData(() => fetchPipelineRun(Number(runId.value)), [runId])
const { data: pipeline, error: pipelineError, loading: pipelineLoading } = useAsyncData(() => fetchPipeline(slug.value), [slug])

// Keep the page live while the run is in progress.
const running = computed(() => run.value?.status === 'RUNNING')
usePolling(reload, running, 3000)

const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  clock = setInterval(() => (now.value = Date.now()), 1000)
})
onBeforeUnmount(() => {
  if (clock) clearInterval(clock)
})

const title = computed(() => `Запуск #${runId.value}`)
const parameters = computed(() => parseRunParameters(run.value?.parametersJson ?? null))
const doneCount = computed(() => run.value?.steps.filter((s) => s.status === 'DONE' || s.status === 'SKIPPED').length ?? 0)
const currentSteps = computed(() => (run.value ? activeSteps(run.value) : []))

const flowNodes = computed(() => (pipeline.value && run.value ? buildStatusNodes(pipeline.value, run.value) : []))
const flowEdges = computed(() => (pipeline.value && run.value ? buildViewEdges(pipeline.value, takenEdgePredicate(run.value)) : []))

function kindLabel(contentType: string): string {
  return BLOCK_KIND_BY_TYPE[contentType as keyof typeof BLOCK_KIND_BY_TYPE]?.label ?? contentType
}
</script>

<template>
  <div>
    <PageHeader eyebrow="Pipeline run" :title="title" :subtitle="pipeline?.name ?? slug">
      <template #actions>
        <PipelineRunStatusBadge v-if="run" :status="run.status" class="pl-run-badge-lg" />
        <RouterLink
          :to="{ name: 'pipeline', params: { project, slug } }"
          class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
        >
          <AppIcon name="arrowLeft" class="size-4" />
          К пайплайну
        </RouterLink>
      </template>
    </PageHeader>

    <ErrorState v-if="error || pipelineError" :message="(error || pipelineError)!" />
    <SkeletonRows v-else-if="(loading && !run) || (pipelineLoading && !pipeline)" :rows="3" />

    <template v-else-if="run && pipeline">
      <div class="pl-run-summary">
        <div class="pl-run-stat">
          <span class="pl-run-stat-label">Прогресс</span>
          <span class="pl-run-stat-value">{{ doneCount }} из {{ run.steps.length }} шагов</span>
        </div>
        <div class="pl-run-stat">
          <span class="pl-run-stat-label">{{ run.status === 'RUNNING' ? (currentSteps.length > 1 ? 'Сейчас параллельно' : 'Сейчас') : 'Остановился на' }}</span>
          <span v-if="currentSteps.length" class="pl-run-stat-value pl-runs-steps">
            <span v-for="step in currentSteps" :key="step.orderIndex" class="pl-runs-step">
              <AppIcon v-if="run.status === 'RUNNING'" name="refresh" class="pl-spin size-3.5 text-[#3b82f6]" />
              <span class="pl-runs-step-index">{{ step.orderIndex + 1 }}</span>
              {{ step.title }}
            </span>
          </span>
          <span v-else class="pl-run-stat-value pl-runs-muted">{{ run.status === 'RUNNING' ? 'все шаги пройдены, ждёт завершения' : 'конец рана' }}</span>
        </div>
        <div class="pl-run-stat">
          <span class="pl-run-stat-label">Начало</span>
          <span class="pl-run-stat-value">{{ formatClock(run.startedAt) }}<span v-if="run.startedBy" class="pl-runs-by"> · {{ run.startedBy }}</span></span>
        </div>
        <div class="pl-run-stat">
          <span class="pl-run-stat-label">Длительность</span>
          <span class="pl-run-stat-value">{{ formatDuration(run.startedAt, run.finishedAt, now) }}</span>
        </div>
      </div>

      <section v-if="parameters.length" class="mb-6">
        <div class="pl-section-head"><h2 class="pl-section-title">Параметры запуска</h2></div>
        <div class="pl-chips">
          <span v-for="p in parameters" :key="p.name" class="pl-chip-param"><b>{{ p.name }}</b> = {{ p.value }}</span>
        </div>
      </section>

      <section class="mb-8">
        <div class="pl-section-head">
          <h2 class="pl-section-title">Путь по схеме</h2>
          <p v-if="running" class="pl-section-note pl-live"><AppIcon name="refresh" class="pl-spin size-3.5" />обновляется каждые 3 секунды</p>
        </div>
        <div class="pl-graph-frame">
          <VueFlow
            :nodes="flowNodes"
            :edges="flowEdges"
            :node-types="{ pipelineStep: PipelineMiniStepNode }"
            :nodes-draggable="false"
            :nodes-connectable="false"
            :edges-updatable="false"
            :zoom-on-scroll="false"
            :min-zoom="0.15"
            fit-view-on-init
            class="pl-flow-readonly"
          />
        </div>
      </section>

      <section>
        <div class="pl-section-head"><h2 class="pl-section-title">Шаги</h2></div>
        <div class="pl-table-frame">
          <table class="pl-runs-table">
            <thead>
              <tr>
                <th>№</th>
                <th>Шаг</th>
                <th>Статус</th>
                <th>Начало</th>
                <th>Длительность</th>
                <th>Заметка</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="step in run.steps"
                :key="step.id"
                class="pl-runs-row pl-runs-row-static"
                :class="{ 'pl-runs-row-running': stageStatusFor(run, step.orderIndex) === 'running' }"
              >
                <td class="pl-runs-time">{{ step.orderIndex + 1 }}</td>
                <td>
                  <span class="pl-runs-id">{{ step.title }}</span>
                  <span class="pl-runs-by">{{ kindLabel(step.contentType) }}</span>
                </td>
                <td>
                  <span class="pl-step-status" :class="`pl-step-status-${stageStatusFor(run, step.orderIndex)}`">
                    <AppIcon v-if="stageStatusFor(run, step.orderIndex) === 'running'" name="refresh" class="pl-spin size-3" />
                    <AppIcon v-else-if="step.status === 'DONE'" name="check" class="size-3" />
                    <AppIcon v-else-if="step.status === 'FAILED'" name="close" class="size-3" />
                    <span v-else class="size-1.5 rounded-full bg-current" />
                    {{ stageStatusFor(run, step.orderIndex) === 'running' ? 'Выполняется' : STEP_STATUS_LABEL[step.status] }}
                  </span>
                </td>
                <td class="pl-runs-time">{{ formatClock(step.startedAt) }}</td>
                <td class="pl-runs-time">{{ step.startedAt ? formatDuration(step.startedAt, step.finishedAt, now) : '' }}</td>
                <td class="pl-runs-note">{{ step.note ?? '' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>
