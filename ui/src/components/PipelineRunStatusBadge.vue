<script setup lang="ts">
import type { PipelineRunStatus } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import { RUN_STATUS_LABEL } from '@/lib/pipelineRuns'

// Run status pill. RUNNING gets the spinning blue arrows so an in-progress run is obvious at a glance.
defineProps<{ status: PipelineRunStatus }>()
</script>

<template>
  <span class="pl-run-badge" :class="`pl-run-badge-${status.toLowerCase()}`">
    <AppIcon v-if="status === 'RUNNING'" name="refresh" class="pl-spin size-3" />
    <AppIcon v-else-if="status === 'DONE'" name="check" class="size-3" />
    <AppIcon v-else-if="status === 'FAILED'" name="close" class="size-3" />
    <span v-else class="size-1.5 rounded-full bg-current" />
    {{ RUN_STATUS_LABEL[status] }}
  </span>
</template>
