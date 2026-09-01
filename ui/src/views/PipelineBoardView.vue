<script setup lang="ts">
import '@vue-flow/core/dist/style.css'
import '@vue-flow/node-resizer/dist/style.css'

import { VueFlow, type EdgeMouseEvent, type NodeDragEvent, type NodeMouseEvent } from '@vue-flow/core'
import { computed, ref, toRef, watch } from 'vue'

import { fetchPipeline, updatePipeline, uploadPipelineAsset } from '@/api/client'
import type {
  PipelineConditionOperator,
  PipelineStepContentType,
  PipelineUpsertDataLink,
  PipelineUpsertOutput,
  PipelineUpsertParameter,
  PipelineUpsertRoute,
  PipelineUpsertStep,
} from '@/api/types'
import ErrorState from '@/components/ErrorState.vue'
import PipelineStepNode from '@/components/PipelineStepNode.vue'

// Unlike PipelineBuilderView (the metadata screen), the board always operates on an already-created
// pipeline - there is no "new" mode here, a slug is required to have anything to draw.
const props = defineProps<{ project: string; slug: string }>()
const project = toRef(props, 'project')
const slug = toRef(props, 'slug')

const name = ref('')
// Metadata fields the board doesn't edit but must round-trip unchanged on save, since
// updatePipeline replaces the whole pipeline definition in one request.
const description = ref<string | null>(null)
const parameters = ref<PipelineUpsertParameter[]>([])
const steps = ref<PipelineUpsertStep[]>([])
const loading = ref(true)
const loadError = ref<string | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)

const END_NODE_ID = 'end'
const endPosition = ref({ x: 480, y: 120 })
const DEFAULT_SIZE = { width: 280, height: 220 }
// Card sizes are session-local, not persisted - resizing here is purely a canvas-editing
// convenience, no backend field exists to round-trip it across reloads yet.
const nodeSizes = ref<Record<number, { width: number; height: number }>>({})

const selectedStepIndex = ref<number | null>(null)
const selectedEdge = ref<{ stepIndex: number; routeIndex: number } | null>(null)

const boardHint = ref<string | null>(null)
let hintTimer: ReturnType<typeof setTimeout> | null = null
function showHint(text: string) {
  boardHint.value = text
  if (hintTimer) clearTimeout(hintTimer)
  hintTimer = setTimeout(() => {
    boardHint.value = null
  }, 4000)
}

function edgeId(stepIndex: number, route: PipelineUpsertRoute): string {
  const targetId = route.targetStepIndex === null ? END_NODE_ID : String(route.targetStepIndex)
  return `${stepIndex}-${route.outcomeKey ?? 'default'}-${targetId}`
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const pipeline = await fetchPipeline(slug.value)
    name.value = pipeline.name
    description.value = pipeline.description
    parameters.value = pipeline.parameters.map((p) => ({
      name: p.name,
      label: p.label,
      type: p.type,
      required: p.required,
      defaultValue: p.defaultValue,
    }))
    steps.value = pipeline.steps.map((s) => ({
      title: s.title,
      contentType: s.contentType,
      promptText: s.promptText,
      assetId: s.assetId,
      referenceAssetId: s.referenceAssetId,
      positionX: s.positionX,
      positionY: s.positionY,
      routes: s.routes.map((r) => ({ outcomeKey: r.outcomeKey, targetStepIndex: r.targetStepOrderIndex })),
      outputs: s.outputs.map((o) => ({ name: o.name })),
      dataLinksOut: s.dataLinksOut.map((l) => ({ token: l.token, sourceOutputName: l.sourceOutputName, targetStepIndex: l.targetStepOrderIndex })),
      conditionOperator: s.conditionOperator,
      conditionValue: s.conditionValue,
    }))
    applyLegacyAutoLayoutIfNeeded()
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}

function applyLegacyAutoLayoutIfNeeded() {
  const allAtOrigin = steps.value.length > 0 && steps.value.every((s) => s.positionX === 0 && s.positionY === 0)
  if (!allAtOrigin) return
  steps.value.forEach((step, index) => {
    step.positionX = index * 300
    step.positionY = 0
  })
  endPosition.value = { x: steps.value.length * 300, y: 0 }
}

watch(slug, load, { immediate: true })

function addStep(kind: PipelineStepContentType) {
  const offset = steps.value.length * 300
  const base = {
    title: '',
    contentType: kind,
    promptText: '',
    assetId: null,
    referenceAssetId: null,
    positionX: offset,
    positionY: 220,
    routes: [] as PipelineUpsertRoute[],
    outputs: [] as PipelineUpsertOutput[],
    dataLinksOut: [] as PipelineUpsertDataLink[],
    conditionOperator: null as PipelineConditionOperator | null,
    conditionValue: null as string | null,
  }
  if (kind === 'CONDITION') {
    base.routes = [
      { outcomeKey: 'true', targetStepIndex: null },
      { outcomeKey: 'false', targetStepIndex: null },
    ]
    base.conditionOperator = 'EQUALS'
    base.conditionValue = ''
  } else if (kind === 'VARIABLE') {
    base.outputs = [{ name: 'value' }]
  }
  steps.value.push(base)
}

function removeStep(index: number) {
  steps.value.splice(index, 1)
  delete nodeSizes.value[index]
  // Routes AND data links referencing this step by index are now stale (indices shifted) - drop
  // anything that pointed at the removed step, and shift down everything that pointed past it, so
  // a save can't silently rewire to the wrong node (or get rejected by the backend's ancestor
  // check because a stale targetStepIndex points past the end of the shrunk steps array).
  steps.value.forEach((step) => {
    step.routes = step.routes
      .filter((r) => r.targetStepIndex !== index)
      .map((r) => ({
        ...r,
        targetStepIndex: r.targetStepIndex !== null && r.targetStepIndex > index ? r.targetStepIndex - 1 : r.targetStepIndex,
      }))
    step.dataLinksOut = step.dataLinksOut
      .filter((l) => l.targetStepIndex !== index)
      .map((l) => ({
        ...l,
        targetStepIndex: l.targetStepIndex !== null && l.targetStepIndex > index ? l.targetStepIndex - 1 : l.targetStepIndex,
      }))
  })
  selectedStepIndex.value = null
  selectedEdge.value = null
}

function addOutput(stepIndex: number) {
  steps.value[stepIndex].outputs.push({ name: '' })
}

function renameOutput(stepIndex: number, outputIndex: number, newName: string) {
  const step = steps.value[stepIndex]
  const oldName = step.outputs[outputIndex].name
  step.outputs[outputIndex].name = newName
  // Data links reference outputs by NAME. Without this remap a rename leaves links pointing at
  // the old name and the backend rejects the save with "wires an output it never declared".
  step.dataLinksOut.forEach((link) => {
    if (link.sourceOutputName === oldName) {
      link.sourceOutputName = newName
    }
  })
}

function removeOutput(stepIndex: number, outputIndex: number) {
  const removedName = steps.value[stepIndex].outputs[outputIndex].name
  steps.value[stepIndex].outputs.splice(outputIndex, 1)
  // A data link wiring the removed output would silently point at nothing - drop it rather than
  // leave a dangling {{data:...}} token with no declared pin behind it.
  steps.value[stepIndex].dataLinksOut = steps.value[stepIndex].dataLinksOut.filter(
    (link) => link.sourceOutputName !== removedName,
  )
}

function wiredInputsFor(stepIndex: number): { token: string; sourceStepTitle: string; sourceOutputName: string }[] {
  const result: { token: string; sourceStepTitle: string; sourceOutputName: string }[] = []
  steps.value.forEach((step, sourceIndex) => {
    step.dataLinksOut.forEach((link) => {
      if (link.targetStepIndex === stepIndex) {
        result.push({ token: link.token, sourceStepTitle: step.title || `Шаг ${sourceIndex + 1}`, sourceOutputName: link.sourceOutputName })
      }
    })
  })
  return result
}

async function onMdFileChosen(index: number, event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  const asset = await uploadPipelineAsset(file)
  steps.value[index].assetId = asset.id
}

async function onReferenceFileChosen(index: number, event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  const asset = await uploadPipelineAsset(file)
  steps.value[index].referenceAssetId = asset.id
}

const flowNodes = computed(() => [
  ...steps.value.map((step, index) => {
    const size = nodeSizes.value[index] ?? DEFAULT_SIZE
    return {
      id: String(index),
      type: 'pipelineStep',
      position: { x: step.positionX, y: step.positionY },
      style: { width: `${size.width}px`, height: `${size.height}px` },
      class: selectedStepIndex.value === index ? 'pipeline-node pipeline-node-selected' : 'pipeline-node',
      data: {
        step,
        isEnd: false,
        wiredInputs: wiredInputsFor(index),
        onRemove: () => removeStep(index),
        onAddOutput: () => addOutput(index),
        onRemoveOutput: (outputIndex: number) => removeOutput(index, outputIndex),
        onRenameOutput: (outputIndex: number, value: string) => renameOutput(index, outputIndex, value),
        onMdFileChosen: (event: Event) => onMdFileChosen(index, event),
        onReferenceFileChosen: (event: Event) => onReferenceFileChosen(index, event),
        onResize: (nextSize: { width: number; height: number }) => {
          nodeSizes.value[index] = nextSize
        },
      },
    }
  }),
  {
    id: END_NODE_ID,
    type: 'pipelineStep',
    position: endPosition.value,
    class: 'pipeline-node pipeline-node-end',
    data: { step: null, label: 'Конец рана', isEnd: true },
  },
])

const flowEdges = computed(() => [
  ...steps.value.flatMap((step, index) =>
    step.routes.map((route) => ({
      id: edgeId(index, route),
      source: String(index),
      sourceHandle: step.contentType === 'CONDITION' ? `route-${route.outcomeKey}` : 'route',
      target: route.targetStepIndex === null ? END_NODE_ID : String(route.targetStepIndex),
      targetHandle: 'data-in',
      label: route.outcomeKey ?? '(по умолчанию)',
    })),
  ),
  ...steps.value.flatMap((step, index) =>
    step.dataLinksOut.map((link) => ({
      id: `data-${link.token}`,
      source: String(index),
      sourceHandle: `output-${link.sourceOutputName}`,
      target: String(link.targetStepIndex),
      targetHandle: 'data-in',
      class: 'pipeline-data-edge',
      style: { strokeDasharray: '4 4', stroke: '#10b981' },
    })),
  ),
])

function onNodeDragStop({ node }: NodeDragEvent) {
  if (node.id === END_NODE_ID) {
    endPosition.value = { x: node.position.x, y: node.position.y }
    return
  }
  const index = Number(node.id)
  steps.value[index].positionX = node.position.x
  steps.value[index].positionY = node.position.y
}

function onNodeClick({ node }: NodeMouseEvent) {
  selectedEdge.value = null
  selectedStepIndex.value = node.id === END_NODE_ID ? null : Number(node.id)
}

function onEdgeClick({ edge }: EdgeMouseEvent) {
  selectedStepIndex.value = null
  const stepIndex = Number(edge.source)
  const routeIndex = steps.value[stepIndex].routes.findIndex((r) => edgeId(stepIndex, r) === edge.id)
  selectedEdge.value = routeIndex >= 0 ? { stepIndex, routeIndex } : null
}

function onConnect(connection: { source: string; target: string; sourceHandle?: string | null; targetHandle?: string | null }) {
  // NOTE: per the backend's graph-validation rule, once ANY step has an explicit route, every
  // step's execution edges come only from its own explicit routes. So wiring a data link into a
  // CONDITION step from a source step that has no route of its own will pass here but fail
  // validation on save (ancestor-reachability check). We don't auto-create that connecting route -
  // out of scope for this task; the backend's error message is expected to guide the author.
  const sourceIndex = Number(connection.source)
  const sourceStep = steps.value[sourceIndex]

  if (connection.sourceHandle && connection.sourceHandle.startsWith('output-')) {
    if (connection.target === END_NODE_ID) {
      // The End node has no promptText/step to attach a {{data:...}} token to - a data link
      // into it would crash on `Number('end')` (NaN) below, and it's meaningless anyway.
      return
    }
    const sourceOutputName = connection.sourceHandle.slice('output-'.length)
    if (!sourceOutputName.trim()) {
      showHint('Сначала дайте выходу имя — потом тяните от него связь.')
      return
    }
    const targetIndex = Number(connection.target)
    const token = crypto.randomUUID()
    sourceStep.dataLinksOut.push({ token, sourceOutputName, targetStepIndex: targetIndex })
    const target = steps.value[targetIndex]
    target.promptText = `${target.promptText ?? ''}\n{{data:${token}}}`
    selectedStepIndex.value = null
    selectedEdge.value = null
    return
  }

  const targetIndex = connection.target === END_NODE_ID ? null : Number(connection.target)

  // A Condition step's two routes are fixed at creation time (outcomeKey 'true'/'false', see
  // addStep) - dragging from its `route-true`/`route-false` handle rewires that EXISTING route's
  // target rather than appending a new one, since the graph-shape validation requires exactly
  // those two keys and no more.
  if (sourceStep.contentType === 'CONDITION' && connection.sourceHandle?.startsWith('route-')) {
    const outcomeKey = connection.sourceHandle.slice('route-'.length)
    const routeIndex = sourceStep.routes.findIndex((r) => r.outcomeKey === outcomeKey)
    if (routeIndex >= 0) {
      sourceStep.routes[routeIndex].targetStepIndex = targetIndex
      selectedStepIndex.value = null
      selectedEdge.value = { stepIndex: sourceIndex, routeIndex }
    }
    return
  }

  sourceStep.routes.push({ outcomeKey: null, targetStepIndex: targetIndex })
  selectedStepIndex.value = null
  selectedEdge.value = { stepIndex: sourceIndex, routeIndex: sourceStep.routes.length - 1 }
}

function removeSelectedRoute() {
  if (!selectedEdge.value) return
  const { stepIndex, routeIndex } = selectedEdge.value
  const step = steps.value[stepIndex]
  if (step.contentType === 'CONDITION') {
    // A Condition step must always keep exactly its two true/false routes - "removing" one here
    // just clears its target back to unwired rather than deleting the route entry itself.
    step.routes[routeIndex].targetStepIndex = null
  } else {
    step.routes.splice(routeIndex, 1)
  }
  selectedEdge.value = null
}

const selectedRoute = computed(() =>
  selectedEdge.value ? steps.value[selectedEdge.value.stepIndex].routes[selectedEdge.value.routeIndex] : null,
)
const selectedRouteIsConditionBranch = computed(() =>
  selectedEdge.value ? steps.value[selectedEdge.value.stepIndex].contentType === 'CONDITION' : false,
)

// A text <input v-model="route.outcomeKey"> that the user types into and clears yields "" rather
// than null. The backend's default-route matching checks outcomeKey() == null specifically, so an
// empty string would silently break the "empty = default route" fallback the UI advertises.
function normalizedSteps(): PipelineUpsertStep[] {
  return steps.value.map((step) => {
    // Defensive: a link whose output no longer exists (state saved by an older UI build before
    // rename-sync existed) would be rejected by the backend - drop it instead of blocking the save.
    const declaredOutputs = new Set(step.outputs.map((o) => o.name))
    return {
      ...step,
      routes: step.routes.map((route) => ({
        ...route,
        outcomeKey: route.outcomeKey && route.outcomeKey.trim() !== '' ? route.outcomeKey : null,
      })),
      dataLinksOut: step.dataLinksOut.filter((link) => declaredOutputs.has(link.sourceOutputName)),
    }
  })
}

async function save() {
  const unnamedIndex = steps.value.findIndex((step) => step.outputs.some((o) => !o.name.trim()))
  if (unnamedIndex >= 0) {
    saveError.value = `У шага «${steps.value[unnamedIndex].title || `Шаг ${unnamedIndex + 1}`}» есть выход без имени — назовите его перед сохранением.`
    return
  }
  saving.value = true
  saveError.value = null
  try {
    const request = {
      slug: slug.value,
      name: name.value,
      description: description.value,
      projectScope: project.value,
      parameters: parameters.value,
      steps: normalizedSteps(),
    }
    await updatePipeline(slug.value, request)
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="flex h-screen flex-col bg-elevated">
    <header class="flex shrink-0 items-center justify-between gap-3 border-b border-border bg-panel px-4 py-2.5">
      <div class="flex min-w-0 items-center gap-3">
        <RouterLink
          :to="{ name: 'pipeline', params: { project, slug } }"
          class="flex items-center justify-center rounded-lg border border-border p-1.5 text-faint transition hover:border-accent/40 hover:text-accent"
          title="Назад к пайплайну"
        >
          ←
        </RouterLink>
        <div class="min-w-0">
          <p class="truncate text-[13px] font-semibold text-content">{{ name || slug }}</p>
          <p class="truncate text-[11px] text-faint">{{ slug }}</p>
        </div>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep('PROMPT')">+ Prompt</button>
        <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep('CONDITION')">+ Condition</button>
        <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep('VARIABLE')">+ Variable</button>
        <RouterLink
          :to="{ name: 'pipeline-edit', params: { project, slug } }"
          class="rounded-lg border border-border bg-panel px-3 py-1.5 text-[12.5px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
        >
          Настройки
        </RouterLink>
        <button
          type="button"
          :disabled="saving"
          class="rounded-lg bg-accent px-3 py-1.5 text-[12.5px] font-medium text-accent-fg transition hover:bg-accent-hover disabled:opacity-50"
          @click="save"
        >
          {{ saving ? 'Сохранение…' : 'Сохранить' }}
        </button>
      </div>
    </header>

    <ErrorState v-if="loadError || saveError" :message="(loadError || saveError)!" class="m-3 shrink-0" />

    <div v-if="!loading" class="relative min-h-0 flex-1">
      <VueFlow
        :nodes="flowNodes"
        :edges="flowEdges"
        :node-types="{ pipelineStep: PipelineStepNode }"
        :nodes-connectable="true"
        fit-view-on-init
        @node-drag-stop="onNodeDragStop"
        @node-click="onNodeClick"
        @edge-click="onEdgeClick"
        @connect="onConnect"
      />

      <div
        v-if="boardHint"
        class="absolute bottom-4 left-1/2 -translate-x-1/2 rounded-lg border border-border bg-panel px-4 py-2 text-[12.5px] text-content shadow-lg"
      >
        {{ boardHint }}
      </div>

      <!-- The only editing surface still outside the node itself: a free-form route's outcome key
           (branching on an arbitrary Claude-reported outcome), floated over the canvas instead of
           a permanently-docked side panel so the board stays full-bleed the rest of the time. -->
      <div v-if="selectedRoute" class="absolute right-4 bottom-4 w-72 rounded-xl border border-border bg-panel p-4 shadow-lg">
        <div class="mb-2 flex items-center justify-between">
          <span class="text-[12px] font-semibold text-content">Маршрут</span>
          <button type="button" class="text-faint hover:text-red-600" @click="removeSelectedRoute">✕</button>
        </div>
        <template v-if="!selectedRouteIsConditionBranch">
          <label class="mb-1 block text-[11.5px] font-medium text-muted">Ключ outcome</label>
          <input
            v-model="selectedRoute.outcomeKey"
            placeholder="пусто = маршрут по умолчанию"
            class="w-full rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content"
          />
          <p class="mt-2 text-[11px] text-faint">
            Claude должен вернуть это значение как outcome в pipeline_run_step_update. Пустое значение — маршрут
            по умолчанию (если outcome не передан или не совпал ни с одним другим маршрутом).
          </p>
        </template>
        <p v-else class="text-[11.5px] text-faint">
          Ветка "{{ selectedRoute.outcomeKey }}" условия — целевой шаг выбирается перетаскиванием провода на
          канвасе, ключ фиксирован.
        </p>
      </div>
    </div>
  </div>
</template>
