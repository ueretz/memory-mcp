<script setup lang="ts">
import { computed } from 'vue'

import type { MemoryType } from '@/api/types'

const props = withDefaults(defineProps<{ type: MemoryType; variant?: 'dot' | 'pill' }>(), {
  variant: 'pill',
})

// Written out in full so Tailwind can see every class it needs to generate.
const DOT: Record<MemoryType, string> = {
  USER: 'bg-type-user',
  FEEDBACK: 'bg-type-feedback',
  PROJECT: 'bg-type-project',
  REFERENCE: 'bg-type-reference',
  LOCATION: 'bg-type-location',
}

const PILL: Record<MemoryType, string> = {
  USER: 'text-type-user bg-type-user/10 ring-type-user/20',
  FEEDBACK: 'text-type-feedback bg-type-feedback/10 ring-type-feedback/20',
  PROJECT: 'text-type-project bg-type-project/10 ring-type-project/20',
  REFERENCE: 'text-type-reference bg-type-reference/10 ring-type-reference/20',
  LOCATION: 'text-type-location bg-type-location/10 ring-type-location/20',
}

const dotClass = computed(() => DOT[props.type])
const pillClass = computed(() => PILL[props.type])
</script>

<template>
  <span
    v-if="variant === 'dot'"
    :class="dotClass"
    class="inline-block size-2 shrink-0 rounded-full"
    :title="type"
  />
  <span
    v-else
    :class="pillClass"
    class="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[10.5px] font-semibold tracking-wide uppercase ring-1 ring-inset"
  >
    <span :class="dotClass" class="size-1.5 rounded-full" />
    {{ type }}
  </span>
</template>
