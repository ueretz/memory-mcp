<script setup lang="ts">
import { computed } from 'vue'

import type { AgentTaskType } from '@/api/types'

const props = withDefaults(defineProps<{ type: AgentTaskType; variant?: 'dot' | 'pill' }>(), {
  variant: 'pill',
})

// Written out in full so Tailwind can see every class it needs to generate.
const DOT: Record<AgentTaskType, string> = {
  ANALYSIS: 'bg-agent-analysis',
  IMPLEMENTATION: 'bg-agent-implementation',
  TESTING: 'bg-agent-testing',
  REVIEW: 'bg-agent-review',
  REPORTING: 'bg-agent-reporting',
}

const PILL: Record<AgentTaskType, string> = {
  ANALYSIS: 'text-agent-analysis bg-agent-analysis/10 ring-agent-analysis/20',
  IMPLEMENTATION: 'text-agent-implementation bg-agent-implementation/10 ring-agent-implementation/20',
  TESTING: 'text-agent-testing bg-agent-testing/10 ring-agent-testing/20',
  REVIEW: 'text-agent-review bg-agent-review/10 ring-agent-review/20',
  REPORTING: 'text-agent-reporting bg-agent-reporting/10 ring-agent-reporting/20',
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
