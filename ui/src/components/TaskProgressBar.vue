<script setup lang="ts">
import { computed } from 'vue'

import type { AgentTaskStatus, AgentTaskSummary, TaskProgress, TaskStatus } from '@/api/types'

import TaskProgressRing from './TaskProgressRing.vue'

/**
 * The task's progress from the inside: the same ring the cards show, next to a bar split by the
 * board's own statuses in the board's own colours. The headline numbers come from the server's
 * `progress`, the segments from the subtasks already loaded on this page - so the bar explains
 * *why* the ring stands where it stands.
 */
const props = defineProps<{
  status: TaskStatus
  progress: TaskProgress
  agentTasks: AgentTaskSummary[]
}>()

const SEGMENTS: Array<{ status: AgentTaskStatus; label: string; bar: string; dot: string }> = [
  { status: 'DONE', label: 'Done', bar: 'bg-agent-done', dot: 'bg-agent-done' },
  { status: 'IN_PROGRESS', label: 'In Progress', bar: 'bg-agent-in-progress', dot: 'bg-agent-in-progress' },
  { status: 'BLOCKED', label: 'Blocked', bar: 'bg-agent-blocked', dot: 'bg-agent-blocked' },
  { status: 'TODO', label: 'To Do', bar: 'bg-agent-todo', dot: 'bg-agent-todo' },
]

const segments = computed(() => {
  const total = props.agentTasks.length
  return SEGMENTS.map((segment) => {
    const count = props.agentTasks.filter((agentTask) => agentTask.status === segment.status).length
    return { ...segment, count, width: total === 0 ? 0 : (count / total) * 100 }
  })
})

const headline = computed(() => {
  if (props.status === 'CANCELLED') {
    return 'Cancelled'
  }
  if (props.progress.total === 0) {
    return props.status === 'DONE' ? 'Done, no subtasks on the board' : 'No subtasks on the board yet'
  }
  return `${props.progress.done} of ${props.progress.total} subtasks done`
})
</script>

<template>
  <section class="mb-9 flex items-center gap-4 rounded-2xl border border-border bg-panel p-4">
    <TaskProgressRing :status="status" :progress="progress" :size="52" />
    <div class="flex min-w-0 flex-1 flex-col gap-2">
      <div class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <span class="text-[13px] font-medium text-content">{{ headline }}</span>
        <!-- Not for a closed task: the ring reads 100% from the status while the board may still
             hold unfinished subtasks, and printing both numbers side by side just contradicts
             itself. The counts in the headline stay honest either way. -->
        <span
          v-if="status === 'ACTIVE' && progress.total > 0"
          class="text-[12.5px] text-muted tabular-nums"
        >
          {{ progress.percent }}%
        </span>
      </div>
      <div
        v-if="status !== 'CANCELLED' && agentTasks.length > 0"
        class="flex h-2 overflow-hidden rounded-full bg-elevated"
      >
        <div
          v-for="segment in segments"
          :key="segment.status"
          :class="segment.bar"
          :style="{ width: `${segment.width}%` }"
        />
      </div>
      <div v-if="agentTasks.length > 0" class="flex flex-wrap gap-x-4 gap-y-1">
        <span
          v-for="segment in segments"
          :key="segment.status"
          class="flex items-center gap-1.5 text-[11.5px] text-faint"
        >
          <span class="size-1.5 rounded-full" :class="segment.dot" />
          {{ segment.label }}
          <span class="font-medium text-muted tabular-nums">{{ segment.count }}</span>
        </span>
      </div>
    </div>
  </section>
</template>
