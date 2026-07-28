<script setup lang="ts">
import type { ProjectSummary } from '@/api/types'
import { projectLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'

defineProps<{ project: ProjectSummary }>()
</script>

<template>
  <RouterLink
    :to="projectLocation(project.projectScope)"
    class="group relative overflow-hidden rounded-2xl border border-border bg-panel p-5 transition duration-200 hover:-translate-y-0.5 hover:border-accent/40 hover:shadow-panel"
  >
    <div
      class="pointer-events-none absolute -top-16 -right-16 size-32 rounded-full bg-accent/10 opacity-0 blur-2xl transition duration-300 group-hover:opacity-100"
    />

    <div class="flex items-start justify-between gap-3">
      <span
        class="flex size-10 items-center justify-center rounded-xl border border-border bg-elevated text-muted transition group-hover:border-accent/30 group-hover:text-accent"
      >
        <AppIcon name="folder" class="size-5" />
      </span>
      <AppIcon
        name="chevron"
        class="size-4 text-faint transition group-hover:translate-x-0.5 group-hover:text-accent"
      />
    </div>

    <h3 class="mt-4 truncate text-[15px] font-semibold tracking-tight text-content">
      {{ project.projectScope }}
    </h3>

    <div class="mt-3 flex items-center gap-4 text-[12.5px] text-muted">
      <span class="inline-flex items-center gap-1.5">
        <AppIcon name="document" class="size-3.5 text-faint" />
        {{ project.commonEntryCount }} common
      </span>
      <span class="inline-flex items-center gap-1.5">
        <AppIcon name="task" class="size-3.5 text-faint" />
        {{ project.taskCount }} {{ project.taskCount === 1 ? 'task' : 'tasks' }}
      </span>
    </div>
  </RouterLink>
</template>
