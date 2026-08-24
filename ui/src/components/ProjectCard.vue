<script setup lang="ts">
import type { ProjectSummary } from '@/api/types'
import { projectLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'
import ConstellationField from './ConstellationField.vue'

defineProps<{ project: ProjectSummary }>()
</script>

<template>
  <RouterLink
    :to="projectLocation(project.projectScope)"
    class="group flex flex-col overflow-hidden rounded-2xl border border-border bg-panel transition duration-200 hover:-translate-y-1 hover:border-accent/40 hover:shadow-panel"
  >
    <div
      class="relative flex h-24 shrink-0 items-center justify-center overflow-hidden"
      style="background: linear-gradient(135deg, color-mix(in oklab, var(--color-accent) 24%, transparent), color-mix(in oklab, var(--color-accent) 6%, transparent))"
    >
      <ConstellationField :density="22" class="opacity-0 transition-opacity duration-300 group-hover:opacity-100" />
      <AppIcon name="sparkles" class="relative size-9 text-accent" />
      <AppIcon
        name="chevron"
        class="absolute top-2.5 right-2.5 size-4 text-accent/60 transition group-hover:translate-x-0.5 group-hover:text-accent"
      />
    </div>

    <div class="flex flex-1 flex-col gap-1.5 p-3.5">
      <h3 class="truncate text-[14.5px] font-semibold tracking-tight text-content">
        {{ project.projectScope }}
      </h3>

      <div class="mt-auto flex items-center gap-4 pt-2.5 text-[12px] text-muted">
        <span class="inline-flex items-center gap-1.5">
          <AppIcon name="document" class="size-3.5 text-faint" />
          {{ project.commonEntryCount }} common
        </span>
        <span class="inline-flex items-center gap-1.5">
          <AppIcon name="task" class="size-3.5 text-faint" />
          {{ project.taskCount }} {{ project.taskCount === 1 ? 'task' : 'tasks' }}
        </span>
      </div>
    </div>
  </RouterLink>
</template>
