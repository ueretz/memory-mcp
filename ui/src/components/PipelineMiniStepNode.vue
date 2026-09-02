<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import { computed } from 'vue'

import type { PipelineStepContentType } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import { BLOCK_KIND_BY_TYPE } from '@/lib/pipelineBoard'
import type { StageStatus } from '@/lib/pipelineRuns'

/**
 * Compact "stage" node for the READ-ONLY graphs (pipeline page and run page): a status disc, the
 * step title and a one-line meta (kind + status). Not editable - the board is where steps are
 * built. Handle ids follow the board's contract (`flow-in`, `flow-out`, `flow-out-true/false`) so
 * `pipelineGraphView` builds edges the same way for every view.
 *
 * `status`: 'neutral' (definition only, no run selected), a run-step status, or 'end' for the
 * finish node. `current` marks the step the run is standing on - it also shows as running.
 */
const props = defineProps<
  NodeProps<{
    label: string
    status: StageStatus
    contentType?: PipelineStepContentType
    current?: boolean
    note?: string | null
    meta?: string | null
  }>
>()

const STATUS_TEXT: Record<StageStatus, string> = {
  neutral: '',
  pending: 'ожидает',
  running: 'выполняется',
  done: 'готово',
  failed: 'ошибка',
  skipped: 'пропущен',
  end: 'конец рана',
}

const kind = computed(() => (props.data.contentType ? BLOCK_KIND_BY_TYPE[props.data.contentType] : null))
const isCondition = computed(() => props.data.contentType === 'CONDITION')
const metaText = computed(() => {
  if (props.data.meta) return props.data.meta
  const parts = [kind.value?.label.toLowerCase(), STATUS_TEXT[props.data.status]].filter(Boolean)
  return parts.join(' · ')
})
</script>

<template>
  <div
    class="pl-stage"
    :class="[`pl-stage-${data.status}`, { 'pl-stage-current': data.current, 'pl-stage-end-node': data.status === 'end' }]"
    :style="kind ? { '--kind': kind.color } : undefined"
    :title="data.note ?? undefined"
  >
    <Handle id="flow-in" type="target" :position="Position.Left" class="pipeline-mini-handle" />
    <span class="pl-stage-disc">
      <AppIcon v-if="data.status === 'running'" name="refresh" class="pl-spin size-3.5" />
      <AppIcon v-else-if="data.status === 'done'" name="check" class="size-3.5" />
      <AppIcon v-else-if="data.status === 'failed'" name="close" class="size-3.5" />
      <AppIcon v-else-if="data.status === 'end'" name="flag" class="size-3.5" />
      <AppIcon v-else-if="data.status === 'neutral' && kind" :name="kind.icon" class="size-3.5" />
      <span v-else-if="data.status === 'skipped'" class="pl-stage-skip" />
      <span v-else class="pl-stage-dot" />
    </span>
    <span class="pl-stage-text">
      <span class="pl-stage-title">{{ data.label }}</span>
      <span v-if="metaText" class="pl-stage-meta">{{ metaText }}</span>
    </span>
    <template v-if="isCondition">
      <Handle id="flow-out-true" type="source" :position="Position.Right" class="pipeline-mini-handle" />
      <Handle id="flow-out-false" type="source" :position="Position.Bottom" class="pipeline-mini-handle" />
    </template>
    <Handle v-else id="flow-out" type="source" :position="Position.Right" class="pipeline-mini-handle" />
  </div>
</template>
