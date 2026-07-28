<script setup lang="ts">
import { computed, toRef } from 'vue'

import { fetchEntry } from '@/api/client'
import type { MemoryEntrySummary } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import MarkdownBody from '@/components/MarkdownBody.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import TypeBadge from '@/components/TypeBadge.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { absoluteDateTime, relativeTime } from '@/lib/format'
import { entryHref, entryLocation } from '@/lib/links'

const props = defineProps<{ project: string; name: string; task?: string }>()

const name = toRef(props, 'name')
const { data: entry, error, loading, reload } = useAsyncData(() => fetchEntry(name.value), [name])

/** Only entries this one actually links to can be resolved to a page. */
const linkTargets = computed(() => {
  const map = new Map<string, string>()
  for (const linked of entry.value?.linkedTo ?? []) {
    const href = entryHref(linked)
    if (href) {
      map.set(linked.name, href)
    }
  }
  return map
})

function resolveLink(target: string): string | null {
  return linkTargets.value.get(target) ?? null
}

const scope = computed(() => {
  const current = entry.value
  if (!current) {
    return ''
  }
  return [current.projectScope, current.taskKey].filter(Boolean).join(' / ') || 'no scope'
})

const links = computed<Array<{ title: string; items: MemoryEntrySummary[] }>>(() =>
  [
    { title: 'Links to', items: entry.value?.linkedTo ?? [] },
    { title: 'Linked from', items: entry.value?.linkedFrom ?? [] },
  ].filter((group) => group.items.length > 0),
)
</script>

<template>
  <div>
    <ErrorState v-if="error" :message="error" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="4" />

    <article v-else-if="entry">
      <header class="mb-6 border-b border-border pb-6">
        <div class="flex flex-wrap items-center gap-3">
          <h1 class="text-2xl font-semibold tracking-tight break-words text-content">
            {{ entry.name }}
          </h1>
          <TypeBadge :type="entry.type" />
        </div>

        <p class="mt-3 text-[14.5px] leading-relaxed text-muted">{{ entry.description }}</p>

        <div class="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2 text-[12px] text-faint">
          <span class="inline-flex items-center gap-1.5">
            <AppIcon name="folder" class="size-3.5" />
            <span class="font-mono">{{ scope }}</span>
          </span>
          <span v-if="entry.filePath" class="inline-flex items-center gap-1.5">
            <AppIcon name="document" class="size-3.5" />
            <span class="font-mono break-all">{{ entry.filePath }}</span>
          </span>
          <span :title="absoluteDateTime(entry.updatedAt)" class="inline-flex items-center gap-1.5">
            <AppIcon name="refresh" class="size-3.5" />
            updated {{ relativeTime(entry.updatedAt) }}
          </span>
        </div>
      </header>

      <MarkdownBody :content="entry.content" :resolve-link="resolveLink" />

      <section v-for="group in links" :key="group.title" class="mt-8">
        <h2 class="mb-2.5 flex items-center gap-2 text-[12px] font-semibold tracking-wide text-muted uppercase">
          <AppIcon name="link" class="size-3.5" />
          {{ group.title }}
        </h2>
        <div class="flex flex-wrap gap-2">
          <component
            :is="entryLocation(linked) ? 'RouterLink' : 'span'"
            v-for="linked in group.items"
            :key="linked.name"
            :to="entryLocation(linked) ?? undefined"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-2.5 py-1.5 text-[12.5px] text-content transition"
            :class="entryLocation(linked) ? 'hover:border-accent/40 hover:text-accent' : 'text-faint'"
          >
            <TypeBadge :type="linked.type" variant="dot" />
            {{ linked.name }}
          </component>
        </div>
      </section>
    </article>
  </div>
</template>
