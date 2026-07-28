<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, type RouteLocationRaw } from 'vue-router'

import { projectLocation, taskLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'

interface Crumb {
  label: string
  to?: RouteLocationRaw
}

const route = useRoute()

/** Derived from route params so every view gets a breadcrumb for free. */
const crumbs = computed<Crumb[]>(() => {
  const items: Crumb[] = [{ label: 'Projects', to: { name: 'projects' } }]
  const project = route.params.project as string | undefined
  const task = route.params.task as string | undefined
  const name = route.params.name as string | undefined

  if (route.name === 'setup') {
    return [items[0], { label: 'Setup' }]
  }
  if (project) {
    items.push({ label: project, to: projectLocation(project) })
  }
  if (task) {
    items.push({ label: task, to: taskLocation(project as string, task) })
  }
  if (name) {
    items.push({ label: name })
  }
  if (route.name === 'project-graph' || route.name === 'task-graph') {
    items.push({ label: 'Graph' })
  }

  const last = items[items.length - 1]
  if (last) {
    delete last.to
  }
  return items
})
</script>

<template>
  <nav aria-label="Breadcrumb" class="flex min-w-0 items-center gap-1 text-[13px]">
    <template v-for="(crumb, index) in crumbs" :key="index">
      <AppIcon v-if="index > 0" name="chevron" class="size-3 shrink-0 text-faint/70" />
      <RouterLink
        v-if="crumb.to"
        :to="crumb.to"
        class="max-w-[10rem] truncate rounded-md px-1.5 py-0.5 text-muted transition hover:bg-elevated hover:text-content sm:max-w-[14rem]"
      >
        {{ crumb.label }}
      </RouterLink>
      <span v-else class="max-w-[12rem] truncate px-1.5 py-0.5 font-medium text-content sm:max-w-[18rem]">
        {{ crumb.label }}
      </span>
    </template>
  </nav>
</template>
