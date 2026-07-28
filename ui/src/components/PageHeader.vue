<script setup lang="ts">
defineProps<{ title: string; subtitle?: string; eyebrow?: string }>()
</script>

<template>
  <header class="mb-7 flex flex-wrap items-end justify-between gap-4">
    <div class="min-w-0">
      <p v-if="eyebrow" class="mb-1 text-[11px] font-semibold tracking-[0.14em] text-faint uppercase">
        {{ eyebrow }}
      </p>
      <div class="flex flex-wrap items-center gap-2.5">
        <h1 class="text-2xl font-semibold tracking-tight break-words text-content">{{ title }}</h1>
        <slot name="title-suffix" />
      </div>
      <p v-if="subtitle" class="mt-1.5 max-w-2xl text-[13.5px] text-muted">{{ subtitle }}</p>
    </div>
    <div class="flex shrink-0 items-center gap-2">
      <slot name="actions" />
    </div>
  </header>
</template>
