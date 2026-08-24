<script setup lang="ts">
import { computed } from 'vue'

import type { FolderSummary } from '@/api/types'
import { folderLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'
import ConstellationField from './ConstellationField.vue'

const props = defineProps<{ folder: FolderSummary; projectScope: string }>()

const to = computed(() => folderLocation(props.projectScope, props.folder.name))
</script>

<template>
  <RouterLink
    :to="to"
    class="group flex flex-col overflow-hidden rounded-2xl border border-border bg-panel transition duration-200 hover:-translate-y-1 hover:border-accent/40 hover:shadow-panel"
  >
    <div
      class="relative flex h-24 shrink-0 items-center justify-center overflow-hidden"
      style="background: linear-gradient(135deg, color-mix(in oklab, var(--color-accent) 24%, transparent), color-mix(in oklab, var(--color-accent) 6%, transparent))"
    >
      <ConstellationField :density="22" class="opacity-0 transition-opacity duration-300 group-hover:opacity-100" />
      <AppIcon name="folder" class="relative size-9 text-accent" />
    </div>

    <div class="flex flex-1 flex-col gap-1.5 p-3.5">
      <span class="truncate text-[13.5px] font-semibold text-content">{{ folder.name }}</span>
      <span class="line-clamp-2 text-[12.5px] leading-snug text-muted">{{ folder.description }}</span>
    </div>
  </RouterLink>
</template>
