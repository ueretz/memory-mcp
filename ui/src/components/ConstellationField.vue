<script setup lang="ts">
withDefaults(defineProps<{ density?: number }>(), { density: 28 })

const patternId = `constellation-${Math.random().toString(36).slice(2)}`
</script>

<template>
  <svg class="pointer-events-none absolute inset-0 size-full" aria-hidden="true">
    <defs>
      <pattern :id="patternId" :width="density" :height="density" patternUnits="userSpaceOnUse">
        <circle cx="2" cy="2" r="1.4" class="fill-border-strong" />
        <line x1="2" y1="2" :x2="density" y2="2" class="stroke-border-strong" stroke-width="0.6" opacity="0.4" />
        <line x1="2" y1="2" x2="2" :y2="density" class="stroke-border-strong" stroke-width="0.6" opacity="0.4" />
      </pattern>
    </defs>
    <rect width="100%" height="100%" :fill="`url(#${patternId})`" />
  </svg>
</template>
