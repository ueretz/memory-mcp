<script setup lang="ts">
import { toRef } from 'vue'

import { fetchPipelines, fetchSettings } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { dataVersion } from '@/lib/dataVersion'

const props = defineProps<{ project: string }>()
const project = toRef(props, 'project')

const { data: settings } = useAsyncData(fetchSettings, [dataVersion])
const { data: pipelines, error, loading, reload } = useAsyncData(fetchPipelines, [dataVersion])
</script>

<template>
  <div>
    <PageHeader eyebrow="Pipelines" title="Пайплайны" subtitle="Общие для всех проектов: любой пайплайн можно запустить из любого репозитория.">
      <template #actions>
        <RouterLink
          :to="{ name: 'pipeline-new', params: { project } }"
          class="inline-flex items-center gap-2 rounded-lg bg-accent px-3 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover"
        >
          <AppIcon name="task" class="size-4" />
          Новый пайплайн
        </RouterLink>
      </template>
    </PageHeader>

    <p v-if="settings && !settings.find((s) => s.key === 'feature.pipelines.enabled' && s.value === 'true')"
       class="mb-6 rounded-xl border border-amber-500/30 bg-amber-500/10 px-4 py-3 text-[13px] text-amber-700">
      Экспериментальная функция «Пайплайны» выключена — Claude Code не сможет их выполнить, пока вы не включите
      флаг в <RouterLink :to="{ name: 'settings' }" class="underline">Настройках</RouterLink>.
    </p>

    <ErrorState v-if="error" :message="error" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="3" />
    <EmptyState v-else-if="!pipelines?.length" icon="task" title="Пока нет ни одного пайплайна" />
    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <RouterLink
        v-for="pipeline in pipelines"
        :key="pipeline.slug"
        :to="{ name: 'pipeline', params: { project, slug: pipeline.slug } }"
        class="rounded-2xl border border-border bg-panel p-5 transition hover:border-accent/40"
      >
        <h2 class="text-[14.5px] font-semibold tracking-tight text-content">{{ pipeline.name }}</h2>
        <p class="mt-1 font-mono text-[12px] text-faint">{{ pipeline.slug }}</p>
        <p v-if="pipeline.description" class="mt-2 text-[13px] text-muted">{{ pipeline.description }}</p>
        <p class="mt-3 text-[12px] text-faint">{{ pipeline.stepCount }} шагов · {{ pipeline.parameterCount }} параметров</p>
      </RouterLink>
    </div>
  </div>
</template>
