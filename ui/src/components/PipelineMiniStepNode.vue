<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'

// Compact GitLab-CI-style status node for the READ-ONLY pipeline views (PipelineView,
// PipelineRunView): a status circle + the step name beside it, instead of the full editable
// card the board uses. Same NodeProps<Data> shape as PipelineStepNode - custom fields live
// under the single `data` prop.
//
// `status`: 'neutral' (definition view, no run), 'pending' | 'running' | 'done' | 'failed'
// | 'skipped' (run view), 'end' (the finish node). `current` draws the highlight ring on the
// step the run is standing on. Handle ids mirror PipelineStepNode's contract so the views'
// edges (route / route-true / route-false -> data-in) keep working unchanged.
defineProps<
  NodeProps<{
    label: string
    status: 'neutral' | 'pending' | 'running' | 'done' | 'failed' | 'skipped' | 'end'
    contentType?: string
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
</script>

<template>
  <div class="pipeline-mini" :class="[`pipeline-mini-${data.status}`, { 'pipeline-mini-current': data.current }]" :title="data.note ?? undefined">
    <Handle id="data-in" type="target" :position="Position.Left" class="pipeline-mini-handle" />
    <span class="pipeline-mini-circle">{{ STATUS_ICON[data.status] }}</span>
    <span class="pipeline-mini-label">{{ data.label }}</span>
    <template v-if="data.contentType === 'CONDITION'">
      <Handle id="route-true" type="source" :position="Position.Right" class="pipeline-mini-handle" />
      <Handle id="route-false" type="source" :position="Position.Bottom" class="pipeline-mini-handle" />
    </template>
    <Handle v-else id="route" type="source" :position="Position.Right" class="pipeline-mini-handle" />
  </div>
</template>
