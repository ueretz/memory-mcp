<script setup lang="ts">
import { computed } from 'vue'

import type { TaskSummary } from '@/api/types'
import { relativeTime } from '@/lib/format'
import { taskLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'
import StatusBadge from './StatusBadge.vue'

const props = defineProps<{ task: TaskSummary; projectScope: string }>()

const to = computed(() => taskLocation(props.projectScope, props.task.taskKey))
</script>

<template>
  <RouterLink
    :to="to"
    class="group flex flex-col overflow-hidden rounded-2xl border border-border bg-panel transition duration-200 hover:-translate-y-1 hover:border-accent/40 hover:shadow-panel"
  >
    <div
      class="relative flex h-24 shrink-0 items-center justify-center"
      style="background: linear-gradient(135deg, color-mix(in oklab, var(--color-accent) 24%, transparent), color-mix(in oklab, var(--color-accent) 6%, transparent))"
    >
      <AppIcon name="task" class="size-9 text-accent" />
      <span class="absolute top-2.5 right-2.5">
        <StatusBadge :status="task.status" />
      </span>
    </div>

    <div class="flex flex-1 flex-col gap-1.5 p-3.5">
      <span class="flex items-center gap-2">
        <span class="truncate font-mono text-[13px] font-semibold text-content">{{ task.taskKey }}</span>
        <span v-if="task.source === 'JIRA'" class="text-[11px] font-medium text-faint">Jira</span>
      </span>
      <span class="line-clamp-2 text-[12.5px] leading-snug text-muted">{{ task.title || 'No title' }}</span>

      <time class="mt-auto pt-2.5 text-[11.5px] text-faint" :datetime="task.updatedAt">
        {{ relativeTime(task.updatedAt) }}
      </time>
    </div>
  </RouterLink>
</template>
