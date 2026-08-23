<script setup lang="ts">
import { computed } from 'vue'

import type { FolderSummary } from '@/api/types'
import { folderLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'

const props = defineProps<{ folder: FolderSummary; projectScope: string }>()

const to = computed(() => folderLocation(props.projectScope, props.folder.name))
</script>

<template>
  <RouterLink
    :to="to"
    class="group flex items-center gap-3.5 rounded-xl border border-border bg-panel px-3.5 py-2.5 transition duration-150 hover:-translate-y-px hover:border-accent/40 hover:shadow-panel"
  >
    <span
      class="flex size-8 shrink-0 items-center justify-center rounded-lg border border-border bg-elevated text-muted transition group-hover:border-accent/30 group-hover:text-accent"
    >
      <AppIcon name="folder" class="size-4" />
    </span>

    <span class="min-w-0 flex-1">
      <span class="truncate text-[13.5px] font-medium text-content">{{ folder.name }}</span>
      <span class="mt-0.5 block truncate text-[12.5px] text-muted">{{ folder.description }}</span>
    </span>

    <AppIcon
      name="chevron"
      class="size-3.5 shrink-0 text-faint transition group-hover:translate-x-0.5 group-hover:text-accent"
    />
  </RouterLink>
</template>
