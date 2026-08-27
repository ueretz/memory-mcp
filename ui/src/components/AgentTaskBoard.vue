<script setup lang="ts">
import { computed } from 'vue'

import type { AgentTaskStatus, AgentTaskSummary } from '@/api/types'

import AgentTaskCard from './AgentTaskCard.vue'
import EmptyState from './EmptyState.vue'

const props = defineProps<{ agentTasks: AgentTaskSummary[] }>()

const COLUMNS: Array<{ status: AgentTaskStatus; label: string; dot: string }> = [
  { status: 'TODO', label: 'To Do', dot: 'bg-agent-todo' },
  { status: 'IN_PROGRESS', label: 'In Progress', dot: 'bg-agent-in-progress' },
  { status: 'BLOCKED', label: 'Blocked', dot: 'bg-agent-blocked' },
  { status: 'DONE', label: 'Done', dot: 'bg-agent-done' },
]

const grouped = computed(() => {
  const map = new Map<AgentTaskStatus, AgentTaskSummary[]>()
  for (const column of COLUMNS) {
    map.set(column.status, [])
  }
  for (const agentTask of props.agentTasks) {
    map.get(agentTask.status)?.push(agentTask)
  }
  return map
})
</script>

<template>
  <EmptyState
    v-if="agentTasks.length === 0"
    icon="task"
    title="No subtasks on this board yet"
    hint="The agent-task-board skill creates and drives these as it works through the task."
  />
  <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
    <div v-for="column in COLUMNS" :key="column.status" class="flex flex-col gap-3">
      <h3 class="flex items-center gap-2 text-[12px] font-semibold tracking-wide text-muted uppercase">
        <span class="size-2 rounded-full" :class="column.dot" />
        {{ column.label }}
        <span class="rounded-full bg-elevated px-1.5 py-0.5 text-[11px] font-medium text-muted tabular-nums">
          {{ grouped.get(column.status)?.length ?? 0 }}
        </span>
      </h3>
      <div class="flex flex-col gap-2.5">
        <AgentTaskCard v-for="agentTask in grouped.get(column.status)" :key="agentTask.id" :agent-task="agentTask" />
      </div>
    </div>
  </div>
</template>
