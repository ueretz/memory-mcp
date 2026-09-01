<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

import { VueFlow } from '@vue-flow/core'
import { computed, ref, toRef } from 'vue'
import { useRouter } from 'vue-router'

import { deletePipeline, fetchPipeline, fetchPipelineRuns } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import PipelineMiniStepNode from '@/components/PipelineMiniStepNode.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { projectLocation } from '@/lib/links'

const props = defineProps<{ project: string; slug: string }>()
const project = toRef(props, 'project')
const slug = toRef(props, 'slug')

const { data: pipeline, error, loading } = useAsyncData(() => fetchPipeline(slug.value), [slug])
const { data: runs, loading: runsLoading } = useAsyncData(() => fetchPipelineRuns(slug.value), [slug])

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

const END_NODE_ID = 'end'
const STEP_SPACING = 220

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

// Read-only views draw compact GitLab-CI-style status nodes (PipelineMiniStepNode), not the
// full editable cards - the board is where step contents are inspected and edited.
const flowNodes = computed(() => {
  if (!pipeline.value) return []
  const steps = pipeline.value.steps
  const positions = stepPositions.value
  const maxX = steps.length > 0 ? Math.max(...steps.map((s) => positions.get(s.orderIndex)!.x)) : 0
  return [
    ...steps.map((step) => ({
      id: String(step.orderIndex),
      type: 'pipelineStep',
      position: positions.get(step.orderIndex)!,
      data: {
        label: `${step.orderIndex + 1}. ${step.title}`,
        status: 'neutral',
        contentType: step.contentType,
      },
    })),
    {
      id: END_NODE_ID,
      type: 'pipelineStep',
      position: { x: maxX + 240, y: 0 },
      data: { label: 'Конец', status: 'end' },
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

const STATUS_LABEL: Record<string, string> = {
  RUNNING: 'Выполняется',
  DONE: 'Готово',
  FAILED: 'Ошибка',
  ABORTED: 'Прервано',
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
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
          >
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

      <SkeletonRows v-if="loading" :rows="2" class="mb-6" />

      <section v-else-if="pipeline" class="mb-9">
        <h2 class="mb-3 text-[13px] font-semibold tracking-wide text-content uppercase">Шаги</h2>
        <div class="h-[360px] overflow-hidden rounded-xl border border-border bg-elevated">
          <VueFlow :nodes="flowNodes" :edges="flowEdges" :node-types="{ pipelineStep: PipelineMiniStepNode }" :nodes-draggable="false" :edges-updatable="false" fit-view-on-init />
        </div>
      </section>

      <section>
        <h2 class="mb-3 text-[13px] font-semibold tracking-wide text-content uppercase">История запусков</h2>
        <SkeletonRows v-if="runsLoading" :rows="2" />
        <EmptyState v-else-if="!runs?.length" icon="task" title="Пока не было ни одного запуска" />
        <ul v-else class="space-y-2">
          <li v-for="run in runs" :key="run.id">
            <RouterLink
              :to="{ name: 'pipeline-run', params: { project, slug, runId: run.id } }"
              class="flex items-center justify-between rounded-xl border border-border bg-panel px-4 py-3 transition hover:border-accent/40"
            >
              <span class="text-[13px] text-content">Запуск #{{ run.id }}</span>
              <span class="text-[12px] text-faint">{{ STATUS_LABEL[run.status] }} · {{ new Date(run.startedAt).toLocaleString() }}</span>
            </RouterLink>
          </li>
        </ul>
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
