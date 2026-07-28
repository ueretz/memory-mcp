<script setup lang="ts">
import { computed, toRef } from 'vue'

import { fetchEntries, fetchTasks } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryRow from '@/components/EntryRow.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { graphLocation } from '@/lib/links'

const props = defineProps<{ project: string; task: string }>()

const project = toRef(props, 'project')
const taskKey = toRef(props, 'task')

const { data: entries, error, loading, reload } = useAsyncData(
  () => fetchEntries(project.value, taskKey.value),
  [project, taskKey],
)

const { data: tasks } = useAsyncData(() => fetchTasks(project.value), [project])

const task = computed(() => (tasks.value ?? []).find((item) => item.taskKey === taskKey.value) ?? null)
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Task"
      :title="taskKey"
      :subtitle="task?.title || 'Working notes scoped to this task.'"
    >
      <template #title-suffix>
        <StatusBadge v-if="task" :status="task.status" />
      </template>
      <template #actions>
        <RouterLink
          :to="graphLocation(project, taskKey)"
          class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
        >
          <AppIcon name="graph" class="size-4" />
          Graph
        </RouterLink>
      </template>
    </PageHeader>

    <ErrorState v-if="error" :message="error" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="3" />
    <EmptyState
      v-else-if="!entries?.length"
      icon="document"
      title="No entries saved for this task yet"
      hint="Entries Claude scopes to this task will collect here."
    />
    <div v-else class="space-y-2">
      <EntryRow v-for="entry in entries" :key="entry.name" :entry="entry" />
    </div>
  </div>
</template>
