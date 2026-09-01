<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'

// Declared as the full `NodeProps<Data>` (not just `{ data: ... }`) because vue-flow's
// `:node-types` prop requires each component to be assignable to `NodeComponent`, which expects
// the whole NodeProps shape (id, type, selected, connectable, ...) - a component typed with only
// a `data` prop type-checks fine on its own but fails to satisfy `NodeComponent` when registered.
defineProps<
  NodeProps<{
    label: string
    outputs: { name: string }[]
    contentType: string
  }>
>()
</script>

<template>
  <div>
    <Handle id="data-in" type="target" :position="Position.Left" class="!h-2.5 !w-2.5 !bg-sky-500" />
    <Handle id="route" type="source" :position="Position.Right" class="!h-2.5 !w-2.5 !bg-content" />

    <span>{{ data.label }}</span>

    <div v-for="(output, index) in data.outputs" :key="output.name" class="relative mt-1 text-[10.5px] text-muted">
      {{ output.name }}
      <Handle
        :id="`output-${output.name}`"
        type="source"
        :position="Position.Right"
        class="!h-2 !w-2 !bg-emerald-500"
        :style="{ top: `${8 + (index + 1) * 16}px` }"
      />
    </div>
  </div>
</template>
