<script setup lang="ts">
import { computed, ref } from 'vue'

import { fetchPipelines, fetchSettings } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import PipelineRunStatusBadge from '@/components/PipelineRunStatusBadge.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { usePolling } from '@/composables/usePolling'
import { dataVersion } from '@/lib/dataVersion'
import { relativeTime } from '@/lib/format'

// Pipelines are shared across projects, so this list is global - the same from every project.
const { data: settings } = useAsyncData(fetchSettings, [dataVersion])
const { data: pipelines, error, loading, reload } = useAsyncData(fetchPipelines, [dataVersion])

const query = ref('')
const filtered = computed(() => {
  const q = query.value.trim().toLowerCase()
  const list = pipelines.value ?? []
  if (!q) return list
  return list.filter((p) => p.name.toLowerCase().includes(q) || p.slug.toLowerCase().includes(q) || (p.description ?? '').toLowerCase().includes(q))
})

// Keep the status column live while anything is running.
const anyRunning = computed(() => pipelines.value?.some((p) => p.lastRunStatus === 'RUNNING') ?? false)
usePolling(reload, anyRunning, 5000)

const enabled = computed(() => settings.value?.some((s) => s.key === 'feature.pipelines.enabled' && s.value === 'true') ?? true)
</script>

<template>
  <div>
    <PageHeader eyebrow="Pipelines" title="Пайплайны" subtitle="Общие для всех проектов: собираются на доске, запускаются из любого репозитория.">
      <template #actions>
        <RouterLink
          :to="{ name: 'pipeline-new' }"
          class="inline-flex items-center gap-2 rounded-lg bg-accent px-3 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover"
        >
          <AppIcon name="plus" class="size-4" />
          Новый пайплайн
        </RouterLink>
      </template>
    </PageHeader>

    <p v-if="!enabled" class="mb-6 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-[13px] text-amber-700">
      Экспериментальная функция «Пайплайны» выключена. Claude Code не сможет их выполнить, пока вы не включите
      флаг в <RouterLink :to="{ name: 'settings' }" class="underline">Настройках</RouterLink>.
    </p>

    <ErrorState v-if="error" :message="error" @retry="reload" />
    <SkeletonRows v-else-if="loading && !pipelines" :rows="3" />
    <EmptyState v-else-if="!pipelines?.length" icon="pipeline" title="Пока нет ни одного пайплайна" hint="Создайте первый: задайте название и параметры, дальше соберите шаги на доске." />
    <template v-else>
      <div class="pl-list-toolbar">
        <label class="pl-list-search">
          <AppIcon name="search" class="size-3.5" />
          <input v-model="query" placeholder="Найти по названию или slug" />
        </label>
        <span class="pl-list-count">{{ filtered.length }} из {{ pipelines.length }}</span>
      </div>

      <div class="pl-table-frame">
        <table class="pl-runs-table pl-list-table">
          <thead>
            <tr>
              <th>Пайплайн</th>
              <th>Последний запуск</th>
              <th>Шаги</th>
              <th>Параметры</th>
              <th>Обновлён</th>
              <th aria-label="Действия" />
            </tr>
          </thead>
          <tbody>
            <tr v-for="pipeline in filtered" :key="pipeline.slug" class="pl-runs-row pl-runs-row-static">
              <td>
                <RouterLink :to="{ name: 'pipeline', params: { slug: pipeline.slug } }" class="pl-list-name">
                  <span class="pl-list-icon"><AppIcon name="pipeline" class="size-4" /></span>
                  <span class="min-w-0">
                    <span class="pl-runs-id">{{ pipeline.name }}</span>
                    <span class="pl-runs-by pl-list-slug">{{ pipeline.slug }}</span>
                    <span v-if="pipeline.description" class="pl-list-desc">{{ pipeline.description }}</span>
                  </span>
                </RouterLink>
              </td>
              <td>
                <RouterLink
                  v-if="pipeline.lastRunStatus && pipeline.lastRunId"
                  :to="{ name: 'pipeline-run', params: { slug: pipeline.slug, runId: pipeline.lastRunId } }"
                  class="pl-list-lastrun"
                >
                  <PipelineRunStatusBadge :status="pipeline.lastRunStatus" />
                  <span class="pl-runs-by">#{{ pipeline.lastRunId }} · {{ pipeline.lastRunStartedAt ? relativeTime(pipeline.lastRunStartedAt) : '' }}</span>
                </RouterLink>
                <span v-else class="pl-runs-muted">ещё не запускался</span>
              </td>
              <td class="pl-runs-time">{{ pipeline.stepCount }}</td>
              <td class="pl-runs-time">{{ pipeline.parameterCount }}</td>
              <td class="pl-runs-time">{{ relativeTime(pipeline.updatedAt) }}</td>
              <td class="pl-runs-open pl-list-actions">
                <RouterLink :to="{ name: 'pipeline-board', params: { slug: pipeline.slug } }" class="pl-icon-btn pl-icon-btn-neutral" title="Открыть доску">
                  <AppIcon name="graph" class="size-3.5" />
                </RouterLink>
                <RouterLink :to="{ name: 'pipeline', params: { slug: pipeline.slug } }" class="pl-icon-btn pl-icon-btn-neutral" title="Открыть">
                  <AppIcon name="chevron" class="size-3.5" />
                </RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
