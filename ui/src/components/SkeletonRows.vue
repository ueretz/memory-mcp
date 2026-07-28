<script setup lang="ts">
withDefaults(defineProps<{ rows?: number }>(), { rows: 4 })
</script>

<template>
  <div class="space-y-2">
    <div
      v-for="row in rows"
      :key="row"
      class="flex animate-pulse items-center gap-3 rounded-xl border border-border bg-panel px-4 py-3.5"
      :style="{ animationDelay: `${row * 60}ms` }"
    >
      <div class="size-8 rounded-lg bg-elevated" />
      <div class="flex-1 space-y-2">
        <div class="h-3 w-1/3 rounded-full bg-elevated" />
        <div class="h-2.5 w-2/3 rounded-full bg-elevated" />
      </div>
    </div>
  </div>
</template>
