<script setup lang="ts">
import AppIcon from './AppIcon.vue'

withDefaults(defineProps<{ icon?: string; title: string; hint?: string }>(), {
  icon: 'sparkles',
  hint: '',
})
</script>

<template>
  <div
    class="flex flex-col items-center gap-2 rounded-2xl border border-dashed border-border bg-panel/40 px-6 py-12 text-center"
  >
    <AppIcon :name="icon" class="size-7 text-faint" />
    <p class="text-sm font-medium text-content">{{ title }}</p>
    <p v-if="hint" class="max-w-sm text-[13px] text-muted">{{ hint }}</p>
    <slot />
  </div>
</template>
