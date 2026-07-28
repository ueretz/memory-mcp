<script setup lang="ts">
import AppIcon from './AppIcon.vue'

defineProps<{ message: string }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="flex items-start gap-3 rounded-2xl border border-type-feedback/30 bg-type-feedback/5 px-5 py-4">
    <AppIcon name="warning" class="mt-0.5 size-5 shrink-0 text-type-feedback" />
    <div class="min-w-0 flex-1">
      <p class="text-sm font-medium text-content">Something went wrong</p>
      <p class="mt-0.5 text-[13px] break-words text-muted">{{ message }}</p>
    </div>
    <button
      type="button"
      class="inline-flex shrink-0 items-center gap-1.5 rounded-lg border border-border bg-panel px-2.5 py-1.5 text-[12.5px] font-medium text-content transition hover:border-border-strong hover:bg-elevated"
      @click="$emit('retry')"
    >
      <AppIcon name="refresh" class="size-3.5" />
      Retry
    </button>
  </div>
</template>
