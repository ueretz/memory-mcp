<script setup lang="ts">
import { computed, ref, toRef } from 'vue'

import { fetchEntries, fetchTasks } from '@/api/client'
import { MEMORY_TYPES, type MemoryType } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryRow from '@/components/EntryRow.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import TaskRow from '@/components/TaskRow.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { graphLocation } from '@/lib/links'

const props = defineProps<{ project: string }>()

const project = toRef(props, 'project')
const typeFilter = ref<MemoryType | null>(null)

const {
  data: common,
  error: entriesError,
  loading: entriesLoading,
  reload: reloadEntries,
} = useAsyncData(() => fetchEntries(project.value, null), [project])

const { data: tasks, loading: tasksLoading } = useAsyncData(() => fetchTasks(project.value), [project])

const visibleEntries = computed(() =>
  (common.value ?? []).filter((entry) => !typeFilter.value || entry.type === typeFilter.value),
)

const activeTasks = computed(() => (tasks.value ?? []).filter((task) => task.status === 'ACTIVE'))
const doneTasks = computed(() => (tasks.value ?? []).filter((task) => task.status === 'DONE'))
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Project"
      :title="project"
      subtitle="Common memory lives here; task-scoped notes are grouped below."
    >
      <template #actions>
        <RouterLink
          :to="graphLocation(project)"
          class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
        >
          <AppIcon name="graph" class="size-4" />
          Graph
        </RouterLink>
      </template>
    </PageHeader>

    <section class="mb-9">
      <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 class="flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
          <AppIcon name="document" class="size-4 text-faint" />
          Common
          <span class="rounded-full bg-elevated px-1.5 py-0.5 text-[11px] font-medium text-muted tabular-nums">
            {{ visibleEntries.length }}
          </span>
        </h2>

        <div class="flex flex-wrap items-center gap-1">
          <button
            type="button"
            class="rounded-full px-2.5 py-1 text-[11.5px] font-medium transition"
            :class="
              typeFilter === null
                ? 'bg-accent-soft text-accent'
                : 'text-muted hover:bg-elevated hover:text-content'
            "
            @click="typeFilter = null"
          >
            All
          </button>
          <button
            v-for="type in MEMORY_TYPES"
            :key="type"
            type="button"
            class="rounded-full px-2.5 py-1 text-[11.5px] font-medium transition"
            :class="
              typeFilter === type
                ? 'bg-accent-soft text-accent'
                : 'text-muted hover:bg-elevated hover:text-content'
            "
            @click="typeFilter = type"
          >
            {{ type }}
          </button>
        </div>
      </div>

      <ErrorState v-if="entriesError" :message="entriesError" @retry="reloadEntries" />
      <SkeletonRows v-else-if="entriesLoading" :rows="3" />
      <EmptyState
        v-else-if="visibleEntries.length === 0"
        icon="document"
        :title="typeFilter ? `No ${typeFilter} entries` : 'No common entries yet'"
        hint="Anything Claude saves without a task scope shows up in this list."
      />
      <div v-else class="space-y-2">
        <EntryRow v-for="entry in visibleEntries" :key="entry.name" :entry="entry" />
      </div>
    </section>

    <section>
      <h2 class="mb-3 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
        <AppIcon name="task" class="size-4 text-faint" />
        Tasks
        <span class="rounded-full bg-elevated px-1.5 py-0.5 text-[11px] font-medium text-muted tabular-nums">
          {{ tasks?.length ?? 0 }}
        </span>
      </h2>

      <SkeletonRows v-if="tasksLoading" :rows="2" />
      <EmptyState
        v-else-if="!tasks?.length"
        icon="task"
        title="No tasks yet"
        hint="Ask Claude to scope work to a task and its notes get their own space."
      />
      <div v-else class="space-y-2">
        <TaskRow
          v-for="task in activeTasks"
          :key="task.taskKey"
          :task="task"
          :project-scope="project"
        />

        <details v-if="doneTasks.length" class="group pt-1">
          <summary
            class="inline-flex cursor-pointer list-none items-center gap-1.5 rounded-lg px-1 py-1.5 text-[12.5px] text-muted transition hover:text-content"
          >
            <AppIcon name="chevron" class="size-3 transition group-open:rotate-90" />
            {{ doneTasks.length }} done
          </summary>
          <div class="mt-2 space-y-2">
            <TaskRow
              v-for="task in doneTasks"
              :key="task.taskKey"
              :task="task"
              :project-scope="project"
            />
          </div>
        </details>
      </div>
    </section>
  </div>
</template>
