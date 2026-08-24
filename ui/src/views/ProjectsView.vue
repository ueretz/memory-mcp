<script setup lang="ts">
import { computed } from 'vue'

import { fetchProjects } from '@/api/client'
import ErrorState from '@/components/ErrorState.vue'
import EmptyState from '@/components/EmptyState.vue'
import PageHeader from '@/components/PageHeader.vue'
import ProjectCard from '@/components/ProjectCard.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'

const { data: projects, error, loading, reload } = useAsyncData(fetchProjects)

const stats = computed(() => {
  const list = projects.value ?? []
  return [
    { label: 'Projects', value: list.length },
    { label: 'Common entries', value: list.reduce((sum, p) => sum + p.commonEntryCount, 0) },
    { label: 'Tasks', value: list.reduce((sum, p) => sum + p.taskCount, 0) },
  ]
})
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Overview"
      title="Projects"
      subtitle="Every project memory-mcp has stored long-term context for."
    />

    <div v-if="projects?.length" class="mb-7 grid grid-cols-3 gap-4">
      <div
        v-for="stat in stats"
        :key="stat.label"
        class="rounded-2xl border border-border bg-panel px-4 py-3.5"
      >
        <p class="text-2xl font-semibold tracking-tight text-content tabular-nums">{{ stat.value }}</p>
        <p class="mt-0.5 text-[12px] text-muted">{{ stat.label }}</p>
      </div>
    </div>

    <ErrorState v-if="error" :message="error" class="mb-6" @retry="reload" />

    <SkeletonRows v-if="loading" :rows="3" />

    <EmptyState
      v-else-if="!projects?.length && !error"
      icon="folder"
      title="No projects yet"
      hint="Save a memory entry from Claude Code and the project shows up here."
    />

    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <ProjectCard v-for="project in projects ?? []" :key="project.projectScope" :project="project" />
    </div>
  </div>
</template>
