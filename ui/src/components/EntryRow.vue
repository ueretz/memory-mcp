<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import type { MemoryEntrySummary } from '@/api/types'
import { relativeTime } from '@/lib/format'
import { entryLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'
import TypeBadge from './TypeBadge.vue'

const props = withDefaults(
  defineProps<{ entry: MemoryEntrySummary; showScope?: boolean; accessCount?: number }>(),
  { showScope: false },
)

const to = computed(() => entryLocation(props.entry))

const scope = computed(() => {
  const parts = [props.entry.projectScope, props.entry.taskKey].filter(Boolean)
  return parts.join(' / ')
})
</script>

<template>
  <component
    :is="to ? RouterLink : 'div'"
    :to="to ?? undefined"
    class="group flex items-center gap-3.5 rounded-xl border border-l-2 border-border bg-panel px-3.5 py-2.5 transition duration-150"
    :style="{ borderLeftColor: `var(--color-type-${entry.type.toLowerCase()})` }"
    :class="to ? 'hover:-translate-y-px hover:border-accent/40 hover:shadow-panel' : 'opacity-80'"
  >
    <span
      class="flex size-8 shrink-0 items-center justify-center rounded-lg border border-border bg-elevated text-muted transition group-hover:border-accent/30 group-hover:text-accent"
    >
      <AppIcon name="document" class="size-4" />
    </span>

    <span class="min-w-0 flex-1">
      <span class="flex items-center gap-2">
        <TypeBadge :type="entry.type" variant="dot" />
        <span class="truncate text-[13.5px] font-medium text-content">{{ entry.name }}</span>
        <span
          v-if="showScope && scope"
          class="hidden truncate rounded-md bg-elevated px-1.5 py-0.5 font-mono text-[11px] text-faint sm:inline"
        >
          {{ scope }}
        </span>
      </span>
      <span class="mt-0.5 block truncate text-[12.5px] text-muted">{{ entry.description }}</span>
    </span>

    <span
      v-if="accessCount !== undefined"
      class="hidden shrink-0 rounded-full bg-elevated px-2 py-0.5 text-[11px] font-medium text-muted tabular-nums sm:inline"
    >
      {{ accessCount }} {{ accessCount === 1 ? 'view' : 'views' }}
    </span>
    <time
      class="hidden shrink-0 text-[12px] whitespace-nowrap text-faint sm:block"
      :datetime="entry.updatedAt"
    >
      {{ relativeTime(entry.updatedAt) }}
    </time>
    <AppIcon
      v-if="to"
      name="chevron"
      class="size-3.5 shrink-0 text-faint transition group-hover:translate-x-0.5 group-hover:text-accent"
    />
  </component>
</template>
