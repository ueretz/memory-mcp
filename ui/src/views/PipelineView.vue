<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

import { VueFlow } from '@vue-flow/core'
import { computed, onBeforeUnmount, onMounted, ref, toRef, watch } from 'vue'
import { useRouter } from 'vue-router'

import { deletePipeline, fetchPipeline, fetchPipelineRun, fetchPipelineRuns } from '@/api/client'
import type { PipelineRunSummary } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import PipelineMiniStepNode from '@/components/PipelineMiniStepNode.vue'
import PipelineRunStatusBadge from '@/components/PipelineRunStatusBadge.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { usePolling } from '@/composables/usePolling'
import { projectLocation } from '@/lib/links'
import { buildStatusNodes, buildViewEdges, takenEdgePredicate } from '@/lib/pipelineGraphView'
import { formatClock, formatDuration } from '@/lib/pipelineRuns'

const props = defineProps<{ project: string; slug: string }>()
const project = toRef(props, 'project')
const slug = toRef(props, 'slug')

const { data: pipeline, error, loading } = useAsyncData(() => fetchPipeline(slug.value), [slug])
const { data: runs, loading: runsLoading, reload: reloadRuns } = useAsyncData(() => fetchPipelineRuns(slug.value), [slug])

// ---- Live status --------------------------------------------------------------------------
// While any run is in progress the history re-fetches every few seconds; the selected run's
// detail (which drives the graph) refreshes on the same cadence while it is RUNNING.
const anyRunning = computed(() => runs.value?.some((r) => r.status === 'RUNNING') ?? false)
usePolling(reloadRuns, anyRunning, 3000)

const selectedRunId = ref<number | null>(null)
const { data: selectedRun, reload: reloadSelectedRun } = useAsyncData(
  () => (selectedRunId.value === null ? Promise.resolve(null) : fetchPipelineRun(selectedRunId.value)),
  [selectedRunId],
)
const selectedRunning = computed(() => selectedRun.value?.status === 'RUNNING')
usePolling(reloadSelectedRun, selectedRunning, 3000)

// Default selection: the newest run still in progress, so opening the page during a run shows it live.
watch(runs, (list) => {
  if (!list || selectedRunId.value !== null) return
  const running = list.find((r) => r.status === 'RUNNING')
  if (running) selectedRunId.value = running.id
})

function selectRun(run: PipelineRunSummary) {
  selectedRunId.value = selectedRunId.value === run.id ? null : run.id
}

// A ticking clock so open-ended durations count up between polls.
const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  clock = setInterval(() => (now.value = Date.now()), 1000)
})
onBeforeUnmount(() => {
  if (clock) clearInterval(clock)
})

const flowNodes = computed(() => (pipeline.value ? buildStatusNodes(pipeline.value, selectedRun.value) : []))
const flowEdges = computed(() =>
  pipeline.value ? buildViewEdges(pipeline.value, selectedRun.value ? takenEdgePredicate(selectedRun.value) : undefined) : [],
)

const selectedSummary = computed(() => runs.value?.find((r) => r.id === selectedRunId.value) ?? null)

function progressPercent(run: PipelineRunSummary): number {
  if (run.totalStepCount === 0) return 0
  return Math.round((run.doneStepCount / run.totalStepCount) * 100)
}

// ---- Delete -------------------------------------------------------------------------------
const router = useRouter()
const showDeleteConfirm = ref(false)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

async function confirmDelete() {
  deleting.value = true
  deleteError.value = null
  try {
    await deletePipeline(slug.value)
    await router.push({ name: 'pipelines', params: { project: project.value } })
  } catch (cause) {
    deleteError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    deleting.value = false
    showDeleteConfirm.value = false
  }
}
</script>

<template>
  <div>
    <ErrorState v-if="error" :message="error" />
    <template v-else>
      <PageHeader eyebrow="Pipeline" :title="pipeline?.name ?? slug" :subtitle="pipeline?.description ?? undefined">
        <template #actions>
          <RouterLink
            :to="{ name: 'pipeline-board', params: { project, slug } }"
            class="inline-flex items-center gap-2 rounded-lg bg-accent px-3 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover"
          >
            <AppIcon name="graph" class="size-4" />
            Открыть доску
          </RouterLink>
          <button
            type="button"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-red-500/50 hover:text-red-600"
            @click="showDeleteConfirm = true"
          >
            <AppIcon name="trash" class="size-4" />
            Удалить
          </button>
          <RouterLink
            :to="projectLocation(project)"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
          >
            <AppIcon name="arrowLeft" class="size-4" />
            Назад
          </RouterLink>
        </template>
      </PageHeader>

      <SkeletonRows v-if="loading && !pipeline" :rows="2" class="mb-6" />

      <section v-else-if="pipeline" class="mb-9">
        <div class="pl-section-head">
          <h2 class="pl-section-title">Схема</h2>
          <div v-if="selectedSummary" class="pl-section-note">
            <span>Показан запуск</span>
            <RouterLink :to="{ name: 'pipeline-run', params: { project, slug, runId: selectedSummary.id } }" class="pl-link">#{{ selectedSummary.id }}</RouterLink>
            <PipelineRunStatusBadge :status="selectedSummary.status" />
            <button type="button" class="pl-link-muted" @click="selectedRunId = null">схема без запуска</button>
          </div>
          <p v-else class="pl-section-note pl-section-note-faint">Выберите запуск в истории, чтобы увидеть его путь по схеме.</p>
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
        <div class="pl-section-head">
          <h2 class="pl-section-title">История запусков</h2>
          <p v-if="anyRunning" class="pl-section-note pl-live">
            <AppIcon name="refresh" class="pl-spin size-3.5" />
            обновляется каждые 3 секунды
          </p>
        </div>
        <SkeletonRows v-if="runsLoading && !runs" :rows="2" />
        <EmptyState v-else-if="!runs?.length" icon="task" title="Пока не было ни одного запуска" />
        <div v-else class="pl-table-frame">
          <table class="pl-runs-table">
            <thead>
              <tr>
                <th>Запуск</th>
                <th>Статус</th>
                <th>Текущий шаг</th>
                <th>Прогресс</th>
                <th>Начало</th>
                <th>Длительность</th>
                <th aria-label="Открыть" />
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="run in runs"
                :key="run.id"
                class="pl-runs-row"
                :class="{ 'pl-runs-row-selected': run.id === selectedRunId, 'pl-runs-row-running': run.status === 'RUNNING' }"
                @click="selectRun(run)"
              >
                <td>
                  <span class="pl-runs-id">#{{ run.id }}</span>
                  <span v-if="run.startedBy" class="pl-runs-by">{{ run.startedBy }}</span>
                </td>
                <td><PipelineRunStatusBadge :status="run.status" /></td>
                <td>
                  <span v-if="run.status === 'RUNNING' && run.activeSteps.length" class="pl-runs-steps">
                    <span v-for="active in run.activeSteps" :key="active.orderIndex" class="pl-runs-step">
                      <AppIcon name="refresh" class="pl-spin size-3.5 text-[#3b82f6]" />
                      <span class="pl-runs-step-index">{{ active.orderIndex + 1 }}</span>
                      <span class="pl-runs-step-title">{{ active.title }}</span>
                    </span>
                  </span>
                  <span v-else-if="run.status === 'RUNNING'" class="pl-runs-muted">все шаги пройдены, ждёт завершения</span>
                  <span v-else class="pl-runs-muted">—</span>
                </td>
                <td>
                  <span class="pl-progress">
                    <span class="pl-progress-bar"><span class="pl-progress-fill" :class="`pl-progress-${run.status.toLowerCase()}`" :style="{ width: `${progressPercent(run)}%` }" /></span>
                    <span class="pl-progress-text">{{ run.doneStepCount }}/{{ run.totalStepCount }}</span>
                  </span>
                </td>
                <td class="pl-runs-time">{{ formatClock(run.startedAt) }}</td>
                <td class="pl-runs-time">{{ formatDuration(run.startedAt, run.finishedAt, now) }}</td>
                <td class="pl-runs-open">
                  <RouterLink :to="{ name: 'pipeline-run', params: { project, slug, runId: run.id } }" class="pl-icon-btn pl-icon-btn-neutral" title="Открыть запуск" @click.stop>
                    <AppIcon name="chevron" class="size-3.5" />
                  </RouterLink>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <ConfirmDialog
      :open="showDeleteConfirm"
      title="Удалить этот пайплайн?"
      message="Определение и история запусков будут удалены безвозвратно."
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="showDeleteConfirm = false"
    />
    <p v-if="deleteError" class="mt-3 text-[12.5px] text-red-600">{{ deleteError }}</p>
  </div>
</template>
