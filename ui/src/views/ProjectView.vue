<script setup lang="ts">
import { computed, ref, toRef } from 'vue'
import { useRouter } from 'vue-router'

import { deleteProject, fetchEntries, fetchFolders, fetchSettings, fetchStats, fetchTasks } from '@/api/client'
import { MEMORY_TYPES, type MemoryType } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryCard from '@/components/EntryCard.vue'
import ErrorState from '@/components/ErrorState.vue'
import FolderCard from '@/components/FolderCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import TaskCard from '@/components/TaskCard.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { dataVersion } from '@/lib/dataVersion'
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

const { data: stats, loading: statsLoading } = useAsyncData(() => fetchStats(project.value, null, 30), [project])

const { data: folders } = useAsyncData(() => fetchFolders(project.value, null, null), [project])

const { data: settings } = useAsyncData(fetchSettings, [dataVersion])

const visibleEntries = computed(() =>
  (common.value ?? []).filter((entry) => !typeFilter.value || entry.type === typeFilter.value),
)

const pipelinesEnabled = computed(
  () => settings.value?.some((s) => s.key === 'feature.pipelines.enabled' && s.value === 'true') ?? false,
)

const activeTasks = computed(() => (tasks.value ?? []).filter((task) => task.status === 'ACTIVE'))
const doneTasks = computed(() => (tasks.value ?? []).filter((task) => task.status === 'DONE'))

const router = useRouter()
const showDeleteConfirm = ref(false)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

const deleteMessage = computed(() => {
  const parts: string[] = []
  if (tasks.value?.length) {
    parts.push(`${tasks.value.length} ${tasks.value.length === 1 ? 'task' : 'tasks'}`)
  }
  if (common.value?.length) {
    parts.push(`${common.value.length} common ${common.value.length === 1 ? 'entry' : 'entries'}`)
  }
  const impact = parts.length ? ` This permanently deletes ${parts.join(', ')} (and everything under them).` : ''
  return `Delete project "${project.value}"?${impact} This can't be undone.`
})

async function confirmDelete() {
  deleting.value = true
  deleteError.value = null
  try {
    await deleteProject(project.value)
    await router.push({ name: 'projects' })
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
    <PageHeader
      eyebrow="Project"
      :title="project"
      subtitle="Common memory lives here; task-scoped notes are grouped below."
    >
      <template #actions>
        <button
          type="button"
          class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-red-500/50 hover:text-red-600"
          @click="showDeleteConfirm = true"
        >
          <AppIcon name="trash" class="size-4" />
          Delete
        </button>
        <RouterLink
          v-if="pipelinesEnabled"
          :to="{ name: 'pipelines', params: { project } }"
          class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
        >
          <AppIcon name="task" class="size-4" />
          Pipelines
        </RouterLink>
        <RouterLink
          :to="graphLocation(project)"
          class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
        >
          <AppIcon name="graph" class="size-4" />
          Graph
        </RouterLink>
      </template>
    </PageHeader>

    <section v-if="folders?.length" class="mb-9">
      <h2 class="mb-3 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
        <AppIcon name="folder" class="size-4 text-faint" />
        Folders
        <span class="rounded-full bg-elevated px-1.5 py-0.5 text-[11px] font-medium text-muted tabular-nums">
          {{ folders.length }}
        </span>
      </h2>
      <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <FolderCard v-for="folder in folders" :key="folder.name" :folder="folder" :project-scope="project" />
      </div>
    </section>

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
      <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <EntryCard v-for="entry in visibleEntries" :key="entry.name" :entry="entry" />
      </div>
    </section>

    <section class="mb-9 rounded-2xl border border-border bg-panel p-5">
      <h2 class="mb-4 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
        <AppIcon name="chart" class="size-4 text-faint" />
        Activity
      </h2>
      <SkeletonRows v-if="statsLoading" :rows="1" />
      <div v-else-if="stats" class="flex flex-wrap gap-8">
        <div>
          <p class="text-2xl font-semibold tracking-tight text-content tabular-nums">
            {{ stats.totals.totalEvents }}
          </p>
          <p class="mt-1 text-[12px] text-faint">Events · last 30 days</p>
        </div>
        <div v-if="stats.topEntries[0]" class="min-w-0">
          <p class="truncate text-2xl font-semibold tracking-tight text-content">
            {{ stats.topEntries[0].name }}
          </p>
          <p class="mt-1 text-[12px] text-faint">Most accessed</p>
        </div>
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
      <div v-else>
        <div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <TaskCard
            v-for="task in activeTasks"
            :key="task.taskKey"
            :task="task"
            :project-scope="project"
          />
        </div>

        <details v-if="doneTasks.length" class="group pt-4">
          <summary
            class="inline-flex cursor-pointer list-none items-center gap-1.5 rounded-lg px-1 py-1.5 text-[12.5px] text-muted transition hover:text-content"
          >
            <AppIcon name="chevron" class="size-3 transition group-open:rotate-90" />
            {{ doneTasks.length }} done
          </summary>
          <div class="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <TaskCard
              v-for="task in doneTasks"
              :key="task.taskKey"
              :task="task"
              :project-scope="project"
            />
          </div>
        </details>
      </div>
    </section>

    <ConfirmDialog
      :open="showDeleteConfirm"
      title="Delete this project?"
      :message="deleteMessage"
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="showDeleteConfirm = false"
    />
    <p v-if="deleteError" class="mt-3 text-[12.5px] text-red-600">{{ deleteError }}</p>
  </div>
</template>
