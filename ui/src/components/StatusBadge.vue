<script setup lang="ts">
import { computed } from 'vue'

import type { TaskStatus } from '@/api/types'

const props = defineProps<{ status: TaskStatus }>()

const STYLES: Record<TaskStatus, string> = {
  ACTIVE: 'text-status-active bg-status-active/10 ring-status-active/20',
  DONE: 'text-status-done bg-status-done/10 ring-status-done/20',
}

const styleClass = computed(() => STYLES[props.status])
</script>

<template>
  <span
    :class="styleClass"
    class="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-[10.5px] font-semibold tracking-wide uppercase ring-1 ring-inset"
  >
    <span class="size-1.5 rounded-full bg-current" />
    {{ status }}
  </span>
</template>
