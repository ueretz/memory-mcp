<script setup lang="ts">
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'

import { fetchProjects } from '@/api/client'
import { useAsyncData } from '@/composables/useAsyncData'
import { projectLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const route = useRoute()
const { data: projects, loading } = useAsyncData(fetchProjects)

const activeProject = computed(() => route.params.project as string | undefined)

// The mobile drawer should never survive a navigation.
watch(() => route.fullPath, () => emit('close'))

const drawerClass = computed(() =>
  props.open ? 'translate-x-0' : '-translate-x-full lg:translate-x-0',
)
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-40 bg-black/40 backdrop-blur-[2px] lg:hidden"
    @click="emit('close')"
  />

  <aside
    :class="drawerClass"
    class="fixed inset-y-0 left-0 z-50 flex w-64 flex-col border-r border-border bg-panel transition-transform duration-200 ease-out lg:z-30"
  >
    <div class="flex h-14 items-center gap-2.5 px-5">
      <RouterLink :to="{ name: 'projects' }" class="flex items-center gap-2.5">
        <span
          class="flex size-7 items-center justify-center rounded-lg bg-accent text-accent-fg shadow-sm"
        >
          <AppIcon name="sparkles" class="size-4" />
        </span>
        <span class="text-[14px] font-semibold tracking-tight text-content">memory-mcp</span>
      </RouterLink>
      <button
        type="button"
        class="ml-auto rounded-lg p-1.5 text-muted transition hover:bg-elevated hover:text-content lg:hidden"
        aria-label="Close navigation"
        @click="emit('close')"
      >
        <AppIcon name="close" class="size-4" />
      </button>
    </div>

    <nav class="px-3 py-2">
      <RouterLink
        :to="{ name: 'projects' }"
        class="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] font-medium text-muted transition hover:bg-elevated hover:text-content"
        exact-active-class="!bg-accent-soft !text-accent"
      >
        <AppIcon name="folder" class="size-4" />
        All projects
      </RouterLink>
      <RouterLink
        :to="{ name: 'stats' }"
        class="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] font-medium text-muted transition hover:bg-elevated hover:text-content"
        active-class="!bg-accent-soft !text-accent"
      >
        <AppIcon name="chart" class="size-4" />
        Statistics
      </RouterLink>
      <RouterLink
        :to="{ name: 'setup' }"
        class="flex items-center gap-2.5 rounded-lg px-2.5 py-2 text-[13px] font-medium text-muted transition hover:bg-elevated hover:text-content"
        active-class="!bg-accent-soft !text-accent"
      >
        <AppIcon name="cog" class="size-4" />
        Setup
      </RouterLink>
    </nav>

    <div class="mt-2 min-h-0 flex-1 overflow-y-auto px-3 pb-4">
      <p class="px-2.5 py-2 text-[10.5px] font-semibold tracking-[0.14em] text-faint uppercase">
        Projects
      </p>

      <div v-if="loading" class="space-y-1.5 px-2.5 py-1">
        <div v-for="row in 4" :key="row" class="h-6 animate-pulse rounded-md bg-elevated" />
      </div>

      <p v-else-if="!projects?.length" class="px-2.5 text-[12.5px] text-faint">Nothing stored yet.</p>

      <RouterLink
        v-for="project in projects ?? []"
        :key="project.projectScope"
        :to="projectLocation(project.projectScope)"
        class="group flex items-center gap-2 rounded-lg px-2.5 py-1.5 text-[13px] transition"
        :class="
          activeProject === project.projectScope
            ? 'bg-accent-soft font-medium text-accent'
            : 'text-muted hover:bg-elevated hover:text-content'
        "
      >
        <span
          class="size-1.5 shrink-0 rounded-full transition"
          :class="activeProject === project.projectScope ? 'bg-accent' : 'bg-border-strong'"
        />
        <span class="truncate">{{ project.projectScope }}</span>
        <span class="ml-auto shrink-0 text-[11px] text-faint tabular-nums">
          {{ project.commonEntryCount + project.taskCount }}
        </span>
      </RouterLink>
    </div>
  </aside>
</template>
