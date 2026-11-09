<script setup lang="ts">
import { computed } from 'vue'

import type { TaskProgress, TaskStatus } from '@/api/types'

import AppIcon from './AppIcon.vue'

/**
 * One glyph for a task's state, for places that show a task from the outside (cards, lists):
 * a check when it's done, a red cross when it was cancelled, otherwise a ring filled to the
 * share of its subtasks that are DONE. The percentage is never typed in anywhere - it comes
 * from the task's own subtask board (see TaskService.progress).
 */
const props = withDefaults(
  defineProps<{ status: TaskStatus; progress?: TaskProgress | null; size?: number }>(),
  { progress: null, size: 38 },
)

// Geometry lives in a fixed 36-unit viewBox and is scaled by `size`, so these numbers hold for
// every call site regardless of how big the ring is rendered.
const RADIUS = 15
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

const percent = computed(() => {
  if (props.status === 'DONE') {
    return 100
  }
  return Math.max(0, Math.min(100, props.progress?.percent ?? 0))
})

const dash = computed(() => `${(percent.value / 100) * CIRCUMFERENCE} ${CIRCUMFERENCE}`)

const label = computed(() => {
  const progress = props.progress
  if (props.status === 'CANCELLED') {
    return 'Task cancelled'
  }
  if (props.status === 'DONE') {
    return progress?.total
      ? `Task done, ${progress.done} of ${progress.total} subtasks`
      : 'Task done'
  }
  return progress?.total
    ? `${progress.done} of ${progress.total} subtasks done (${percent.value}%)`
    : 'No subtasks yet'
})
</script>

<template>
  <span
    class="relative inline-flex shrink-0 items-center justify-center"
    :style="{ width: `${size}px`, height: `${size}px` }"
    role="img"
    :aria-label="label"
  >
    <svg
      class="-rotate-90"
      :width="size"
      :height="size"
      viewBox="0 0 36 36"
      fill="none"
      aria-hidden="true"
    >
      <circle
        cx="18"
        cy="18"
        :r="RADIUS"
        stroke-width="3"
        :class="status === 'CANCELLED' ? 'stroke-status-cancelled/30' : 'stroke-border'"
      />
      <circle
        v-if="status !== 'CANCELLED' && percent > 0"
        cx="18"
        cy="18"
        :r="RADIUS"
        stroke-width="3"
        stroke-linecap="round"
        :stroke-dasharray="dash"
        :class="status === 'DONE' ? 'stroke-status-done' : 'stroke-status-active'"
      />
    </svg>
    <span class="absolute inset-0 flex items-center justify-center">
      <AppIcon v-if="status === 'DONE'" name="check" class="size-4 text-status-done" />
      <AppIcon v-else-if="status === 'CANCELLED'" name="close" class="size-4 text-status-cancelled" />
      <span v-else class="text-[9.5px] leading-none font-semibold text-muted tabular-nums">
        {{ percent }}
      </span>
    </span>
  </span>
</template>
