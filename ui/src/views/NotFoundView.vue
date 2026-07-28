<script setup lang="ts">
import AppIcon from '@/components/AppIcon.vue'
</script>

<template>
  <div class="flex flex-col items-center gap-3 py-20 text-center">
    <span class="flex size-12 items-center justify-center rounded-2xl border border-border bg-panel text-faint">
      <AppIcon name="warning" class="size-6" />
    </span>
    <h1 class="text-xl font-semibold tracking-tight text-content">Page not found</h1>
    <p class="max-w-sm text-[13.5px] text-muted">
      That URL does not match any project, task or entry in memory-mcp.
    </p>
    <RouterLink
      :to="{ name: 'projects' }"
      class="mt-2 inline-flex items-center gap-2 rounded-lg bg-accent px-3 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover"
    >
      <AppIcon name="folder" class="size-4" />
      Back to projects
    </RouterLink>
  </div>
</template>
