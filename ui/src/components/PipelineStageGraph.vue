<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import type { PipelineDetail, PipelineRunDetail } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import { BLOCK_KIND_BY_TYPE } from '@/lib/pipelineBoard'
import type { StageStatus } from '@/lib/pipelineRuns'
import { STAGE_CARD_W, STAGE_HEADER_TOP, columnLeft, layoutStages, type StageNode } from '@/lib/pipelineStageLayout'

/**
 * Read-only pipeline preview in the GitLab CI idiom: stage columns, job cards with a status
 * disc, smooth wires between columns. Pass a run to color the cards by that run's progress;
 * without one the graph shows the definition in neutral colors.
 */
const props = defineProps<{ pipeline: PipelineDetail; run: PipelineRunDetail | null }>()

const layout = computed(() => layoutStages(props.pipeline, props.run))

// Fit the whole graph into the frame when it is wider than the frame (down to a floor, below
// which horizontal scrolling takes over) - so a long pipeline is readable without scrolling.
const MIN_SCALE = 0.6
const frame = ref<HTMLElement | null>(null)
const frameWidth = ref(0)
let observer: ResizeObserver | null = null
onMounted(() => {
  if (!frame.value) return
  frameWidth.value = frame.value.clientWidth
  observer = new ResizeObserver(() => {
    frameWidth.value = frame.value?.clientWidth ?? 0
  })
  observer.observe(frame.value)
})
onBeforeUnmount(() => observer?.disconnect())
watch(layout, () => {
  frameWidth.value = frame.value?.clientWidth ?? frameWidth.value
})
const scale = computed(() => {
  if (!frameWidth.value || layout.value.width <= frameWidth.value) return 1
  return Math.max(MIN_SCALE, frameWidth.value / layout.value.width)
})

const STATUS_TEXT: Record<StageStatus, string> = {
  neutral: '',
  pending: 'ожидает',
  running: 'выполняется',
  done: 'готово',
  failed: 'ошибка',
  skipped: 'пропущен',
  end: '',
}

function kindOf(node: StageNode) {
  return node.step ? BLOCK_KIND_BY_TYPE[node.step.contentType] : null
}

function metaFor(node: StageNode): string {
  const kind = kindOf(node)
  return [kind?.label.toLowerCase(), STATUS_TEXT[node.status]].filter(Boolean).join(' · ')
}

function hubLabel(node: StageNode): string {
  return node.step?.title?.trim() || kindOf(node)?.label || ''
}
</script>

<template>
  <div ref="frame" class="pl-sg-frame" :style="{ height: `${Math.round(layout.height * scale)}px` }">
    <div class="pl-sg" :style="{ width: `${layout.width}px`, height: `${layout.height}px`, transform: `scale(${scale})` }">
      <svg class="pl-sg-edges" :width="layout.width" :height="layout.height" aria-hidden="true">
        <path
          v-for="edge in layout.edges"
          :key="edge.id"
          :d="edge.path"
          :stroke="edge.color"
          :stroke-width="edge.taken && run ? 2.25 : 1.75"
          :opacity="edge.taken ? 1 : 0.55"
          fill="none"
        />
      </svg>

      <span
        v-for="col in layout.columns"
        :key="`h${col}`"
        class="pl-sg-colhead"
        :style="{ left: `${columnLeft(col - 1)}px`, top: `${STAGE_HEADER_TOP}px`, width: `${STAGE_CARD_W}px` }"
      >
        {{ col === layout.columns ? 'Финиш' : `Этап ${col}` }}
      </span>

      <template v-for="node in layout.nodes" :key="node.id">
        <div
          v-if="node.kind === 'end'"
          class="pl-sg-end"
          :class="`pl-sg-end-${node.status}`"
          :style="{ left: `${node.x}px`, top: `${node.y}px`, height: `${node.h}px` }"
        >
          <AppIcon name="flag" class="size-3.5" />
          Конец
        </div>

        <div
          v-else-if="node.hub"
          class="pl-sg-hubwrap"
          :style="{ left: `${node.x}px`, top: `${node.y}px`, width: `${node.w}px`, height: `${node.h}px` }"
          :title="node.note ?? undefined"
        >
          <span class="pl-sg-hub" :class="`pl-sg-hub-${node.status}`" :style="{ '--kind': kindOf(node)?.color }">
            <AppIcon :name="kindOf(node)?.icon ?? 'branch'" class="size-4" />
          </span>
          <span class="pl-sg-hub-label">{{ hubLabel(node) }}</span>
        </div>

        <div
          v-else
          class="pl-sg-card"
          :class="[`pl-sg-card-${node.status}`, { 'pl-sg-card-current': node.current }]"
          :style="{ left: `${node.x}px`, top: `${node.y}px`, width: `${node.w}px`, height: `${node.h}px`, '--kind': kindOf(node)?.color }"
          :title="node.note ?? undefined"
        >
          <span class="pl-sg-status">
            <AppIcon v-if="node.status === 'running'" name="refresh" class="pl-spin size-3.5" />
            <AppIcon v-else-if="node.status === 'done'" name="check" class="size-3.5" />
            <AppIcon v-else-if="node.status === 'failed'" name="close" class="size-3.5" />
            <AppIcon v-else-if="node.status === 'neutral'" :name="kindOf(node)?.icon ?? 'sparkles'" class="size-3.5" />
            <span v-else-if="node.status === 'skipped'" class="pl-sg-skip" />
            <span v-else class="pl-sg-dot" />
          </span>
          <span class="pl-sg-text">
            <span class="pl-sg-title">{{ node.step!.orderIndex + 1 }}. {{ node.step!.title || 'Без названия' }}</span>
            <span class="pl-sg-meta">{{ metaFor(node) }}</span>
          </span>
        </div>
      </template>

      <span
        v-for="edge in layout.edges.filter((e) => e.label)"
        :key="`l${edge.id}`"
        class="pl-sg-edge-label"
        :class="{ 'pl-sg-edge-label-true': edge.label === 'true', 'pl-sg-edge-label-false': edge.label === 'false', 'pl-sg-edge-label-muted': !edge.taken }"
        :style="{ left: `${edge.labelX}px`, top: `${edge.labelY}px` }"
      >
        {{ edge.label }}
      </span>
    </div>
  </div>
</template>
