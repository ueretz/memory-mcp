<script setup lang="ts">
import { toRef } from 'vue'

import { fetchEntry } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import TypeBadge from '@/components/TypeBadge.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { entryLocation, pdfHref } from '@/lib/links'

const props = defineProps<{ project: string; name: string; task?: string }>()

const name = toRef(props, 'name')
const { data: entry, error, loading, reload } = useAsyncData(() => fetchEntry(name.value), [name])
</script>

<template>
  <div class="flex h-screen flex-col bg-bg">
    <header class="flex h-14 shrink-0 items-center gap-3 border-b border-border bg-bg px-4 sm:px-6">
      <RouterLink
        :to="(entry && entryLocation(entry)) ?? { name: 'projects' }"
        class="-ml-1 inline-flex shrink-0 items-center gap-1 rounded-lg p-1.5 text-muted transition hover:bg-elevated hover:text-content"
      >
        <AppIcon name="chevron" class="size-4 rotate-180" />
        <span class="hidden text-[13px] sm:inline">Back to entry</span>
      </RouterLink>

      <template v-if="entry">
        <h1 class="min-w-0 truncate text-[14px] font-semibold text-content">{{ entry.name }}</h1>
        <TypeBadge :type="entry.type" />
      </template>

      <div class="flex-1" />

      <a
        v-if="entry"
        :href="pdfHref(entry.name)"
        class="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-border bg-panel px-2.5 py-1.5 text-[12.5px] text-content transition hover:border-accent/40 hover:text-accent"
      >
        <AppIcon name="download" class="size-3.5" />
        <span class="hidden sm:inline">Download PDF</span>
      </a>
    </header>

    <ErrorState v-if="error" :message="error" class="m-6" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="4" class="m-6" />
    <iframe
      v-else-if="entry"
      :srcdoc="entry.content"
      sandbox="allow-scripts"
      class="min-h-0 flex-1 border-0 bg-white"
      title="Report content"
    />
  </div>
</template>
