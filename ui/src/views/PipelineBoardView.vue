<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

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
import AppIcon from '@/components/AppIcon.vue'
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

const selectedStepIndex = ref<number | null>(null)
const selectedEdge = ref<{ stepIndex: number; routeIndex: number } | null>(null)

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
    step.positionX = index * 220
    step.positionY = 0
  })
  endPosition.value = { x: steps.value.length * 220, y: 0 }
}

watch(slug, load, { immediate: true })

function addStep(kind: PipelineStepContentType) {
  const offset = steps.value.length * 220
  const base = {
    title: '',
    contentType: kind,
    promptText: '',
    assetId: null,
    referenceAssetId: null,
    positionX: offset,
    positionY: 200,
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

function removeOutput(stepIndex: number, outputIndex: number) {
  const removedName = steps.value[stepIndex].outputs[outputIndex].name
  steps.value[stepIndex].outputs.splice(outputIndex, 1)
  // A data link wiring the removed output would silently point at nothing - drop it rather than
  // leave a dangling {{data:...}} token with no declared pin behind it.
  steps.value[stepIndex].dataLinksOut = steps.value[stepIndex].dataLinksOut.filter(
    (link) => link.sourceOutputName !== removedName,
  )
}

// Kept as a script-side helper rather than inlining the template literal in the template's
// mustache interpolation: Vue's template tokenizer finds an interpolation's closing "}}" via a
// plain text scan, not JS-aware parsing, so a literal "}}" inside an inline template-literal
// expression (as in `{{data:${x}}}`) closes the interpolation early and breaks the compile.
function dataToken(token: string): string {
  return `{{data:${token}}}`
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
  ...steps.value.map((step, index) => ({
    id: String(index),
    type: 'pipelineStep',
    position: { x: step.positionX, y: step.positionY },
    class: [
      'pipeline-node',
      step.contentType === 'CONDITION' ? 'pipeline-node-condition' : '',
      selectedStepIndex.value === index ? 'pipeline-node-selected' : '',
    ].filter(Boolean).join(' '),
    data: { label: step.title || `Шаг ${index + 1}`, outputs: step.outputs, contentType: step.contentType },
  })),
  {
    id: END_NODE_ID,
    type: 'pipelineStep',
    position: endPosition.value,
    class: 'pipeline-node pipeline-node-end',
    data: { label: 'Конец рана', outputs: [], contentType: 'PROMPT' },
  },
])

const flowEdges = computed(() => [
  ...steps.value.flatMap((step, index) =>
    step.routes.map((route) => ({
      id: edgeId(index, route),
      source: String(index),
      sourceHandle: 'route',
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

// Best-effort UI hint only - the authoritative check is PipelineService's graph validation on
// save. A step is flagged if something else in the pipeline branches at all, but nothing routes
// into this step and it isn't the first one - i.e. it looks like an unwired, mid-edit node.
const unreachableStepIndexes = computed(() => {
  const anyRoutes = steps.value.some((s) => s.routes.length > 0)
  if (!anyRoutes) return new Set<number>()
  const targeted = new Set<number>()
  steps.value.forEach((step) => {
    step.routes.forEach((route) => {
      if (route.targetStepIndex !== null) targeted.add(route.targetStepIndex)
    })
  })
  const result = new Set<number>()
  steps.value.forEach((_, index) => {
    if (index !== 0 && !targeted.has(index)) result.add(index)
  })
  return result
})

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
  if (connection.sourceHandle && connection.sourceHandle.startsWith('output-')) {
    if (connection.target === END_NODE_ID) {
      // The End node has no promptText/step to attach a {{data:...}} token to - a data link
      // into it would crash on `Number('end')` (NaN) below, and it's meaningless anyway.
      return
    }
    const sourceOutputName = connection.sourceHandle.slice('output-'.length)
    const targetIndex = Number(connection.target)
    const token = crypto.randomUUID()
    steps.value[sourceIndex].dataLinksOut.push({ token, sourceOutputName, targetStepIndex: targetIndex })
    const target = steps.value[targetIndex]
    target.promptText = `${target.promptText ?? ''}\n{{data:${token}}}`
    selectedStepIndex.value = null
    selectedEdge.value = null
    return
  }
  const targetIndex = connection.target === END_NODE_ID ? null : Number(connection.target)
  steps.value[sourceIndex].routes.push({ outcomeKey: null, targetStepIndex: targetIndex })
  selectedStepIndex.value = null
  selectedEdge.value = { stepIndex: sourceIndex, routeIndex: steps.value[sourceIndex].routes.length - 1 }
}

function removeSelectedRoute() {
  if (!selectedEdge.value) return
  steps.value[selectedEdge.value.stepIndex].routes.splice(selectedEdge.value.routeIndex, 1)
  selectedEdge.value = null
}

const selectedStep = computed(() => (selectedStepIndex.value !== null ? steps.value[selectedStepIndex.value] : null))
const selectedRoute = computed(() =>
  selectedEdge.value ? steps.value[selectedEdge.value.stepIndex].routes[selectedEdge.value.routeIndex] : null,
)

// A text <input v-model="route.outcomeKey"> that the user types into and clears yields "" rather
// than null. The backend's default-route matching checks outcomeKey() == null specifically, so an
// empty string would silently break the "empty = default route" fallback the UI advertises.
function normalizedSteps(): PipelineUpsertStep[] {
  return steps.value.map((step) => ({
    ...step,
    routes: step.routes.map((route) => ({
      ...route,
      outcomeKey: route.outcomeKey && route.outcomeKey.trim() !== '' ? route.outcomeKey : null,
    })),
  }))
}

async function save() {
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
          <AppIcon name="arrowLeft" class="size-4" />
        </RouterLink>
        <div class="min-w-0">
          <p class="truncate text-[13px] font-semibold text-content">{{ name || slug }}</p>
          <p class="truncate text-[11px] text-faint">{{ slug }}</p>
        </div>
      </div>
      <div class="flex shrink-0 items-center gap-2">
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

    <div class="flex shrink-0 items-center justify-between gap-3 border-b border-border bg-panel/60 px-4 py-2">
      <p class="text-[11.5px] text-faint">
        Перетащите узел, чтобы разместить его; потяните от одного узла к другому, чтобы создать маршрут или связь.
        Клик по узлу или связи открывает панель редактирования справа.
      </p>
      <div class="flex shrink-0 gap-2">
        <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep('PROMPT')">+ Prompt</button>
        <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep('CONDITION')">+ Condition</button>
        <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep('VARIABLE')">+ Variable</button>
      </div>
    </div>

    <ErrorState v-if="loadError || saveError" :message="(loadError || saveError)!" class="m-3 shrink-0" />

    <div v-if="!loading" class="flex min-h-0 flex-1">
      <div class="min-h-0 flex-1">
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
      </div>
      <aside class="w-80 shrink-0 overflow-y-auto border-l border-border bg-elevated p-4">
        <template v-if="selectedStep && selectedStepIndex !== null">
          <div class="mb-3 flex items-center justify-between">
            <span class="text-[12px] text-faint">Шаг #{{ selectedStepIndex + 1 }}</span>
            <button type="button" class="text-faint hover:text-red-600" @click="removeStep(selectedStepIndex)">
              <AppIcon name="trash" class="size-4" />
            </button>
          </div>
          <span v-if="unreachableStepIndexes.has(selectedStepIndex)" class="mb-2 block text-[11.5px] text-amber-500">
            ⚠ Ни один маршрут не ведёт в этот шаг
          </span>
          <input
            v-model="selectedStep.title"
            placeholder="Название шага"
            class="mb-2 w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
          />
          <template v-if="selectedStep.contentType === 'PROMPT' || selectedStep.contentType === 'MD_FILE'">
            <div class="mb-2 flex gap-3 text-[12.5px] text-muted">
              <label class="flex items-center gap-1"><input v-model="selectedStep.contentType" type="radio" value="PROMPT" /> Prompt</label>
              <label class="flex items-center gap-1"><input v-model="selectedStep.contentType" type="radio" value="MD_FILE" /> .md файл</label>
            </div>
            <textarea
              v-if="selectedStep.contentType === 'PROMPT'"
              v-model="selectedStep.promptText"
              rows="4"
              placeholder="Инструкция для Claude — можно {{paramName}}"
              class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
            />
            <div v-else class="text-[12.5px] text-muted">
              <input type="file" accept=".md" @change="onMdFileChosen(selectedStepIndex, $event)" />
              <span v-if="selectedStep.assetId" class="ml-2">Загружен: asset #{{ selectedStep.assetId }}</span>
            </div>
          </template>
          <div v-else-if="selectedStep.contentType === 'CONDITION'" class="mb-2">
            <label class="mb-1 block text-[12.5px] font-medium text-muted">Оператор сравнения</label>
            <select v-model="selectedStep.conditionOperator" class="mb-2 w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content">
              <option value="EQUALS">равно</option>
              <option value="GREATER_THAN">больше</option>
              <option value="LESS_THAN">меньше</option>
              <option value="GREATER_OR_EQUAL">больше или равно</option>
              <option value="LESS_OR_EQUAL">меньше или равно</option>
            </select>
            <label class="mb-1 block text-[12.5px] font-medium text-muted">Значение для сравнения</label>
            <input
              v-model="selectedStep.conditionValue"
              class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
              placeholder="напр. 10"
            />
            <p class="mt-2 text-[11.5px] text-faint">
              Сравнивается со значением, подключённым через входящую связь (data-link) от другого шага.
              Ветка "true"/"false" выбирается автоматически, без участия Claude.
            </p>
          </div>
          <div v-else-if="selectedStep.contentType === 'VARIABLE'" class="mb-2">
            <label class="mb-1 block text-[12.5px] font-medium text-muted">Значение</label>
            <input
              v-model="selectedStep.promptText"
              class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
              placeholder="напр. hello"
            />
            <p class="mt-2 text-[11.5px] text-faint">
              Это значение публикуется в единственный output шага автоматически при старте рана, без участия Claude.
            </p>
          </div>
          <div v-if="selectedStep.contentType === 'PROMPT' || selectedStep.contentType === 'MD_FILE'" class="mt-3 text-[12.5px] text-muted">
            <label class="mb-1 block">Ссылочный файл (необязательно):</label>
            <input type="file" @change="onReferenceFileChosen(selectedStepIndex, $event)" />
            <span v-if="selectedStep.referenceAssetId" class="ml-2">Загружен: asset #{{ selectedStep.referenceAssetId }}</span>
          </div>
          <div class="mt-3 text-[12.5px] text-muted">
            <div class="mb-1 flex items-center justify-between">
              <label class="font-medium">Выходы (пины)</label>
              <button
                v-if="!(selectedStep.contentType === 'VARIABLE' && selectedStep.outputs.length >= 1)"
                type="button" class="text-accent" @click="addOutput(selectedStepIndex)">+ Выход</button>
            </div>
            <div v-for="(output, outputIndex) in selectedStep.outputs" :key="outputIndex" class="mb-1 flex items-center gap-2">
              <input
                v-model="output.name"
                placeholder="имя, напр. summary"
                class="flex-1 rounded-lg border border-border bg-panel px-2 py-1 text-[12px] text-content"
              />
              <button
                v-if="selectedStep.contentType !== 'VARIABLE'"
                type="button" class="text-faint hover:text-red-600" @click="removeOutput(selectedStepIndex, outputIndex)">
                <AppIcon name="trash" class="size-4" />
              </button>
            </div>
          </div>
          <div v-if="wiredInputsFor(selectedStepIndex).length" class="mt-3 text-[11.5px] text-faint">
            <p class="mb-1 font-medium text-muted">Подключённые входы:</p>
            <p v-for="input in wiredInputsFor(selectedStepIndex)" :key="input.token">
              {{ dataToken(input.token) }} → {{ input.sourceStepTitle }} . {{ input.sourceOutputName }}
            </p>
          </div>
        </template>
        <template v-else-if="selectedRoute">
          <div class="mb-3 flex items-center justify-between">
            <span class="text-[12px] text-faint">Маршрут</span>
            <button type="button" class="text-faint hover:text-red-600" @click="removeSelectedRoute">
              <AppIcon name="trash" class="size-4" />
            </button>
          </div>
          <label class="mb-1 block text-[12.5px] font-medium text-muted">Ключ outcome</label>
          <input
            v-if="steps[selectedEdge!.stepIndex].contentType !== 'CONDITION'"
            v-model="selectedRoute.outcomeKey"
            placeholder="пусто = маршрут по умолчанию"
            class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
          />
          <p v-else class="w-full rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content">
            {{ selectedRoute.outcomeKey }}
          </p>
          <p class="mt-2 text-[11.5px] text-faint">
            Claude должен вернуть это значение как outcome в pipeline_run_step_update, чтобы run пошёл по этой
            связи. Пустое значение — маршрут по умолчанию для этого шага (используется, если outcome не
            передан или не совпал ни с одним другим маршрутом).
          </p>
        </template>
        <p v-else class="text-[12.5px] text-faint">Выберите узел или связь на канвасе.</p>
      </aside>
    </div>
  </div>
</template>
