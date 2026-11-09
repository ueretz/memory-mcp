<script setup lang="ts">
import { computed } from 'vue'

import type { SpecSummary } from '@/api/types'
import { relativeTime } from '@/lib/format'
import { entryLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'

/**
 * The MD entry a task or subtask links as its spec. It is a card rather than a line of text
 * because it is the thing you are meant to open: the description and the running progress live
 * in that entry, not in the task's own body.
 */
const props = withDefaults(
  defineProps<{ spec: SpecSummary; projectScope: string; taskKey: string; compact?: boolean }>(),
  { compact: false },
)

const to = computed(() =>
  entryLocation({ name: props.spec.name, projectScope: props.projectScope, taskKey: props.taskKey }),
)
</script>

<template>
  <RouterLink
    v-if="to"
    :to="to"
    class="group flex min-w-0 items-center gap-3 rounded-xl border border-border bg-panel transition hover:border-accent/40 hover:shadow-panel"
    :class="compact ? 'p-2' : 'p-3.5'"
  >
    <span
      class="flex shrink-0 items-center justify-center rounded-lg bg-accent-soft text-accent"
      :class="compact ? 'size-7' : 'size-10'"
    >
      <AppIcon name="document" :class="compact ? 'size-3.5' : 'size-5'" />
    </span>
    <span class="flex min-w-0 flex-col">
      <span class="flex min-w-0 items-center gap-2">
        <span
          class="truncate font-mono font-medium text-content"
          :class="compact ? 'text-[11.5px]' : 'text-[13px]'"
        >
          {{ spec.name }}
        </span>
        <span v-if="!compact" class="shrink-0 text-[11px] text-faint">MD spec</span>
      </span>
      <span
        v-if="!compact"
        class="line-clamp-2 text-[12.5px] leading-snug text-muted"
      >
        {{ spec.description }}
      </span>
      <time class="text-[11px] text-faint" :datetime="spec.updatedAt">
        {{ relativeTime(spec.updatedAt) }}
      </time>
    </span>
    <AppIcon
      name="chevron"
      class="ml-auto size-3.5 shrink-0 text-faint transition group-hover:text-accent"
    />
  </RouterLink>
</template>
