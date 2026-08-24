<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

import type { MemoryEntrySummary, MemoryType } from '@/api/types'
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

// Written out in full so Tailwind can see every class it needs to generate.
const COVER_ICON: Record<MemoryType, string> = {
  USER: 'user',
  FEEDBACK: 'chat',
  PROJECT: 'document',
  REFERENCE: 'link',
  LOCATION: 'pin',
  REPORT: 'chart',
}
const ICON_TEXT: Record<MemoryType, string> = {
  USER: 'text-type-user',
  FEEDBACK: 'text-type-feedback',
  PROJECT: 'text-type-project',
  REFERENCE: 'text-type-reference',
  LOCATION: 'text-type-location',
  REPORT: 'text-type-report',
}

const coverStyle = computed(() => {
  const token = `var(--color-type-${props.entry.type.toLowerCase()})`
  return {
    background: `linear-gradient(135deg, color-mix(in oklab, ${token} 30%, transparent), color-mix(in oklab, ${token} 8%, transparent))`,
  }
})
</script>

<template>
  <component
    :is="to ? RouterLink : 'div'"
    :to="to ?? undefined"
    class="group flex flex-col overflow-hidden rounded-2xl border border-border bg-panel transition duration-200"
    :class="to ? 'hover:-translate-y-1 hover:border-accent/40 hover:shadow-panel' : 'opacity-80'"
  >
    <div class="relative flex h-24 shrink-0 items-center justify-center" :style="coverStyle">
      <AppIcon :name="COVER_ICON[entry.type]" class="size-9" :class="ICON_TEXT[entry.type]" />
      <span class="absolute top-2.5 right-2.5">
        <TypeBadge :type="entry.type" variant="pill" />
      </span>
    </div>

    <div class="flex flex-1 flex-col gap-1.5 p-3.5">
      <span class="truncate text-[13.5px] font-semibold text-content">{{ entry.name }}</span>
      <span class="line-clamp-2 text-[12.5px] leading-snug text-muted">{{ entry.description }}</span>

      <div class="mt-auto flex items-center justify-between gap-2 pt-2.5 text-[11.5px] text-faint">
        <span v-if="showScope && scope" class="truncate rounded-md bg-elevated px-1.5 py-0.5 font-mono">
          {{ scope }}
        </span>
        <span v-else />
        <span class="flex shrink-0 items-center gap-2 whitespace-nowrap">
          <span
            v-if="accessCount !== undefined"
            class="rounded-full bg-elevated px-2 py-0.5 font-medium text-muted tabular-nums"
          >
            {{ accessCount }} {{ accessCount === 1 ? 'view' : 'views' }}
          </span>
          <time :datetime="entry.updatedAt">{{ relativeTime(entry.updatedAt) }}</time>
        </span>
      </div>
    </div>
  </component>
</template>
