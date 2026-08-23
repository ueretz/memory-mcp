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
    class="group flex items-center gap-3.5 rounded-xl border border-border bg-panel px-3.5 py-2.5 transition duration-150 hover:-translate-y-px hover:border-accent/40 hover:shadow-panel"
  >
    <span
      class="flex size-8 shrink-0 items-center justify-center rounded-lg border border-border bg-elevated text-muted transition group-hover:border-accent/30 group-hover:text-accent"
    >
      <AppIcon name="task" class="size-4" />
    </span>

    <span class="min-w-0 flex-1">
      <span class="flex items-center gap-2">
        <span class="truncate font-mono text-[13px] font-semibold text-content">{{ task.taskKey }}</span>
        <StatusBadge :status="task.status" />
        <span v-if="task.source === 'JIRA'" class="text-[11px] font-medium text-faint">Jira</span>
      </span>
      <span class="mt-0.5 block truncate text-[12.5px] text-muted">
        {{ task.title || 'No title' }}
      </span>
    </span>

    <time class="hidden shrink-0 text-[12px] whitespace-nowrap text-faint sm:block" :datetime="task.updatedAt">
      {{ relativeTime(task.updatedAt) }}
    </time>
    <AppIcon
      name="chevron"
      class="size-3.5 shrink-0 text-faint transition group-hover:translate-x-0.5 group-hover:text-accent"
    />
  </RouterLink>
</template>
