<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'

import AppIcon from '@/components/AppIcon.vue'

// The fixed, non-deletable "end of run" node on the board. Only a `flow-in` port: transitions
// wired here persist as routes with a null target, which the engine reads as "run finished".
defineProps<NodeProps<{ wired: boolean }>>()
</script>

<template>
  <div class="pl-end" :class="{ 'pl-end-wired': data.wired }">
    <Handle id="flow-in" type="target" :position="Position.Left" class="pl-pin pl-pin-flow pl-pin-flow-in" :class="{ 'pl-pin-wired': data.wired }" title="Конец рана" />
    <AppIcon name="flag" class="size-3.5" />
    <span>Конец рана</span>
  </div>
</template>
