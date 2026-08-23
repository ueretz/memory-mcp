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
import { entryHref, entryLocation, markdownHref, pdfHref, reportLocation } from '@/lib/links'

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

const isReport = computed(() => entry.value?.type === 'REPORT')

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
          <span v-if="entry.createdBy" class="inline-flex items-center gap-1.5">
            <AppIcon name="sparkles" class="size-3.5" />
            created by {{ entry.createdBy }}
          </span>
          <span :title="absoluteDateTime(entry.updatedAt)" class="inline-flex items-center gap-1.5">
            <AppIcon name="refresh" class="size-3.5" />
            updated {{ relativeTime(entry.updatedAt) }}
          </span>
        </div>

        <div class="mt-4 flex flex-wrap items-center gap-2">
          <a
            :href="pdfHref(entry.name)"
            class="inline-flex items-center gap-1.5 rounded-lg border border-border bg-panel px-2.5 py-1.5 text-[12.5px] text-content transition hover:border-accent/40 hover:text-accent"
          >
            <AppIcon name="download" class="size-3.5" />
            Download PDF
          </a>
          <a
            v-if="!isReport"
            :href="markdownHref(entry.name)"
            class="inline-flex items-center gap-1.5 rounded-lg border border-border bg-panel px-2.5 py-1.5 text-[12.5px] text-content transition hover:border-accent/40 hover:text-accent"
          >
            <AppIcon name="download" class="size-3.5" />
            Download .md
          </a>
        </div>
      </header>

      <div
        v-if="entry.warnings.length > 0"
        class="mb-6 flex items-start gap-3 rounded-2xl border border-type-feedback/30 bg-type-feedback/5 px-5 py-4"
      >
        <AppIcon name="warning" class="mt-0.5 size-5 shrink-0 text-type-feedback" />
        <ul class="min-w-0 flex-1 space-y-1 text-[13px] break-words text-muted">
          <li v-for="warning in entry.warnings" :key="warning">{{ warning }}</li>
        </ul>
      </div>

      <RouterLink
        v-if="isReport"
        :to="reportLocation(entry) ?? {}"
        class="group flex items-center gap-4 rounded-2xl border border-border bg-panel px-5 py-5 transition hover:border-accent/40"
      >
        <span class="flex size-11 shrink-0 items-center justify-center rounded-xl bg-type-report/10 text-type-report">
          <AppIcon name="document" class="size-5" />
        </span>
        <span class="min-w-0 flex-1">
          <span class="block text-[14px] font-semibold text-content">Open report</span>
          <span class="block text-[12.5px] text-muted">
            Reads full-screen on its own page, with its own layout and scripts sandboxed
          </span>
        </span>
        <AppIcon name="enter" class="size-4 shrink-0 text-faint transition group-hover:text-accent" />
      </RouterLink>
      <MarkdownBody v-else :content="entry.content" :resolve-link="resolveLink" />

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
