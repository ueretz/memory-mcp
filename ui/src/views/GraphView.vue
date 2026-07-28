<script setup lang="ts">
import { computed, ref, toRef, useTemplateRef } from 'vue'

import { fetchGraph } from '@/api/client'
import { MEMORY_TYPES, type MemoryType } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import MemoryGraph from '@/components/MemoryGraph.vue'
import PageHeader from '@/components/PageHeader.vue'
import TypeBadge from '@/components/TypeBadge.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { projectLocation, taskLocation } from '@/lib/links'

const props = defineProps<{ project: string; task?: string }>()

const project = toRef(props, 'project')
const taskKey = computed(() => props.task ?? null)
const typeFilter = ref<MemoryType | null>(null)

const { data: graph, error, loading, reload } = useAsyncData(
  () => fetchGraph(project.value, taskKey.value, typeFilter.value),
  [project, taskKey, typeFilter],
)

const graphRef = useTemplateRef<InstanceType<typeof MemoryGraph>>('graph')

const backLink = computed(() =>
  taskKey.value ? taskLocation(project.value, taskKey.value) : projectLocation(project.value),
)
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Graph"
      :title="taskKey ?? project"
      subtitle="Links between entries, derived from [[wiki-links]]. Drag nodes, scroll to zoom, click to open."
    >
      <template #actions>
        <RouterLink
          :to="backLink"
          class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
        >
          <AppIcon name="arrowLeft" class="size-4" />
          Back
        </RouterLink>
      </template>
    </PageHeader>

    <div
      class="overflow-hidden rounded-2xl border border-border bg-panel"
      :style="{
        backgroundImage:
          'radial-gradient(circle at 1px 1px, var(--c-border) 1px, transparent 0)',
        backgroundSize: '22px 22px',
      }"
    >
      <div class="flex flex-wrap items-center gap-2 border-b border-border bg-panel/90 px-3 py-2.5">
        <button
          type="button"
          class="rounded-full px-2.5 py-1 text-[11.5px] font-medium transition"
          :class="
            typeFilter === null
              ? 'bg-accent-soft text-accent'
              : 'text-muted hover:bg-elevated hover:text-content'
          "
          @click="typeFilter = null"
        >
          All types
        </button>
        <button
          v-for="type in MEMORY_TYPES"
          :key="type"
          type="button"
          class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11.5px] font-medium transition"
          :class="
            typeFilter === type
              ? 'bg-accent-soft text-accent'
              : 'text-muted hover:bg-elevated hover:text-content'
          "
          @click="typeFilter = type"
        >
          <TypeBadge :type="type" variant="dot" />
          {{ type }}
        </button>

        <button
          type="button"
          class="ml-auto inline-flex items-center gap-1.5 rounded-lg border border-border px-2.5 py-1 text-[11.5px] font-medium text-muted transition hover:border-border-strong hover:text-content"
          @click="graphRef?.fit()"
        >
          <AppIcon name="refresh" class="size-3.5" />
          Reset view
        </button>
      </div>

      <ErrorState v-if="error" :message="error" class="m-4" @retry="reload" />

      <div v-else-if="loading" class="flex h-[clamp(24rem,62vh,44rem)] items-center justify-center">
        <div class="size-6 animate-spin rounded-full border-2 border-border border-t-accent" />
      </div>

      <EmptyState
        v-else-if="!graph?.nodes.length"
        icon="graph"
        title="Nothing to graph yet"
        hint="Entries appear here once they exist; edges appear once they reference each other."
        class="m-4 border-0 bg-transparent"
      />

      <MemoryGraph
        v-else
        ref="graph"
        :graph="graph"
        :project-scope="project"
        :task-key="taskKey"
      />
    </div>

    <p v-if="graph?.nodes.length" class="mt-3 text-[12.5px] text-faint">
      {{ graph.nodes.length }} {{ graph.nodes.length === 1 ? 'node' : 'nodes' }} ·
      {{ graph.edges.length }} {{ graph.edges.length === 1 ? 'link' : 'links' }}
    </p>
  </div>
</template>
