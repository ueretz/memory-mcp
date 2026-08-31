<script setup lang="ts">
import { computed, ref, toRef } from 'vue'
import { useRouter } from 'vue-router'

import { deleteTask, fetchAgentTasks, fetchEntries, fetchFolders, fetchTasks } from '@/api/client'
import AgentTaskBoard from '@/components/AgentTaskBoard.vue'
import AppIcon from '@/components/AppIcon.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryCard from '@/components/EntryCard.vue'
import ErrorState from '@/components/ErrorState.vue'
import FolderCard from '@/components/FolderCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { entryHref, graphLocation, projectLocation } from '@/lib/links'

const props = defineProps<{ project: string; task: string }>()

const project = toRef(props, 'project')
const taskKey = toRef(props, 'task')

const { data: entries, error, loading, reload } = useAsyncData(
  () => fetchEntries(project.value, taskKey.value),
  [project, taskKey],
)

const { data: tasks } = useAsyncData(() => fetchTasks(project.value), [project])

const { data: folders } = useAsyncData(() => fetchFolders(project.value, taskKey.value, null), [project, taskKey])

const { data: agentTasks } = useAsyncData(() => fetchAgentTasks(project.value, taskKey.value), [project, taskKey])

const task = computed(() => (tasks.value ?? []).find((item) => item.taskKey === taskKey.value) ?? null)

/**
 * Resolves a [[wiki-link]] inside an agent task's description to this task's entries.
 * Agent tasks aren't memory entries themselves (no graph edges), so unlike EntryView this
 * matches by name against the task's already-loaded entry list rather than real linkedTo edges.
 */
function resolveAgentTaskLink(name: string): string | null {
  const match = entries.value?.find((entry) => entry.name === name)
  return match ? entryHref(match) : null
}

const router = useRouter()
const showDeleteConfirm = ref(false)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

const deleteMessage = computed(() => {
  const parts: string[] = []
  if (entries.value?.length) {
    parts.push(`${entries.value.length} ${entries.value.length === 1 ? 'entry' : 'entries'}`)
  }
  if (folders.value?.length) {
    parts.push(`${folders.value.length} ${folders.value.length === 1 ? 'folder' : 'folders'}`)
  }
  if (agentTasks.value?.length) {
    parts.push(`${agentTasks.value.length} agent subtask${agentTasks.value.length === 1 ? '' : 's'}`)
  }
  const impact = parts.length ? ` This permanently deletes ${parts.join(', ')}.` : ''
  return `Delete task "${taskKey.value}"?${impact} This can't be undone.`
})

async function confirmDelete() {
  deleting.value = true
  deleteError.value = null
  try {
    await deleteTask(project.value, taskKey.value)
    await router.push(projectLocation(project.value))
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
      eyebrow="Task"
      :title="taskKey"
      :subtitle="task?.title || 'Working notes scoped to this task.'"
    >
      <template #title-suffix>
        <StatusBadge v-if="task" :status="task.status" />
      </template>
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
          :to="graphLocation(project, taskKey)"
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
      <h2 class="mb-3 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
        <AppIcon name="task" class="size-4 text-faint" />
        Agent Tasks
      </h2>
      <AgentTaskBoard :agent-tasks="agentTasks ?? []" :resolve-link="resolveAgentTaskLink" />
    </section>

    <ErrorState v-if="error" :message="error" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="3" />
    <EmptyState
      v-else-if="!entries?.length"
      icon="document"
      title="No entries saved for this task yet"
      hint="Entries Claude scopes to this task will collect here."
    />
    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <EntryCard v-for="entry in entries" :key="entry.name" :entry="entry" />
    </div>

    <ConfirmDialog
      :open="showDeleteConfirm"
      title="Delete this task?"
      :message="deleteMessage"
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="showDeleteConfirm = false"
    />
    <p v-if="deleteError" class="mt-3 text-[12.5px] text-red-600">{{ deleteError }}</p>
  </div>
</template>
