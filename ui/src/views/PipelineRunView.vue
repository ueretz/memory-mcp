<script setup lang="ts">
import { computed, toRef } from 'vue'

import { fetchPipelineRun } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'

const props = defineProps<{ project: string; slug: string; runId: string }>()
const runId = toRef(props, 'runId')

const { data: run, error, loading } = useAsyncData(() => fetchPipelineRun(Number(runId.value)), [runId])

const STEP_ICON: Record<string, string> = {
  PENDING: 'chevron',
  RUNNING: 'refresh',
  DONE: 'check',
  FAILED: 'warning',
  SKIPPED: 'arrowLeft',
}

const STEP_COLOR: Record<string, string> = {
  PENDING: 'text-faint',
  RUNNING: 'text-accent',
  DONE: 'text-green-600',
  FAILED: 'text-red-600',
  SKIPPED: 'text-faint',
}

const title = computed(() => (run.value ? `Запуск #${run.value.id} — ${run.value.pipelineSlug}` : `Запуск #${runId.value}`))
</script>

<template>
  <div>
    <PageHeader eyebrow="Pipeline run" :title="title" />

    <ErrorState v-if="error" :message="error" />
    <SkeletonRows v-else-if="loading" :rows="3" />

    <ol v-else-if="run" class="space-y-3">
      <li v-for="step in run.steps" :key="step.id" class="rounded-2xl border border-border bg-panel p-4">
        <div class="flex items-center gap-2.5">
          <AppIcon :name="STEP_ICON[step.status]" class="size-4" :class="STEP_COLOR[step.status]" />
          <span class="text-[13.5px] font-medium text-content">{{ step.orderIndex + 1 }}. {{ step.title }}</span>
        </div>
        <p v-if="step.note" class="mt-2 ml-6.5 text-[12.5px] text-muted">{{ step.note }}</p>
      </li>
    </ol>
  </div>
</template>
