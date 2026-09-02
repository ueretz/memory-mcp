<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import { computed } from 'vue'

import type { PipelineStepContentType } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import { BLOCK_KIND_BY_TYPE } from '@/lib/pipelineBoard'

// Compact GitLab-CI-style status node for the READ-ONLY pipeline views (PipelineView,
// PipelineRunView): a status circle + the step name, instead of the full editable card the
// board uses. Custom fields live under the single `data` prop, same as PipelineStepNode.
//
// `status`: 'neutral' (definition view, no run), 'pending' | 'running' | 'done' | 'failed'
// | 'skipped' (run view), 'end' (the finish node). `current` draws the highlight ring on the
// step the run is standing on. Handle ids mirror the board's contract (`flow-in`, `flow-out`,
// `flow-out-true` / `flow-out-false`) so both views build edges the same way.
const props = defineProps<
  NodeProps<{
    label: string
    status: 'neutral' | 'pending' | 'running' | 'done' | 'failed' | 'skipped' | 'end'
    contentType?: PipelineStepContentType
    current?: boolean
    note?: string | null
  }>
>()

const STATUS_ICON: Record<string, string> = {
  neutral: '',
  pending: '',
  running: '●',
  done: '✓',
  failed: '✕',
  skipped: '⊘',
  end: '⚑',
}

const kind = computed(() => (props.data.contentType ? BLOCK_KIND_BY_TYPE[props.data.contentType] : null))
const isCondition = computed(() => props.data.contentType === 'CONDITION')
</script>

<template>
  <div
    class="pipeline-mini"
    :class="[`pipeline-mini-${data.status}`, { 'pipeline-mini-current': data.current }]"
    :style="kind ? { '--kind': kind.color } : undefined"
    :title="data.note ?? undefined"
  >
    <Handle id="flow-in" type="target" :position="Position.Left" class="pipeline-mini-handle" />
    <span v-if="data.status === 'neutral' && kind" class="pipeline-mini-kind"><AppIcon :name="kind.icon" class="size-3.5" /></span>
    <span v-else class="pipeline-mini-circle">{{ STATUS_ICON[data.status] }}</span>
    <span class="pipeline-mini-label">{{ data.label }}</span>
    <template v-if="isCondition">
      <Handle id="flow-out-true" type="source" :position="Position.Right" class="pipeline-mini-handle" />
      <Handle id="flow-out-false" type="source" :position="Position.Bottom" class="pipeline-mini-handle" />
    </template>
    <Handle v-else id="flow-out" type="source" :position="Position.Right" class="pipeline-mini-handle" />
  </div>
</template>
