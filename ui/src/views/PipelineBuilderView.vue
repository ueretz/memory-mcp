<script setup lang="ts">
import '@vue-flow/core/dist/style.css'

import { VueFlow, type EdgeMouseEvent, type NodeDragEvent, type NodeMouseEvent } from '@vue-flow/core'
import { computed, ref, toRef, watch } from 'vue'
import { useRouter } from 'vue-router'

import { createPipeline, fetchPipeline, updatePipeline, uploadPipelineAsset } from '@/api/client'
import type {
  PipelineParameterType,
  PipelineStepContentType,
  PipelineUpsertParameter,
  PipelineUpsertRoute,
  PipelineUpsertStep,
} from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import PipelineStepNode from '@/components/PipelineStepNode.vue'

const props = defineProps<{ project: string; slug?: string }>()
const project = toRef(props, 'project')
const editingSlug = toRef(props, 'slug')
const isEditing = computed(() => !!editingSlug.value)

const router = useRouter()

const slug = ref('')
const name = ref('')
const description = ref('')
const parameters = ref<PipelineUpsertParameter[]>([])
const steps = ref<PipelineUpsertStep[]>([])
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

async function loadForEdit() {
  if (!editingSlug.value) return
  const pipeline = await fetchPipeline(editingSlug.value)
  slug.value = pipeline.slug
  name.value = pipeline.name
  description.value = pipeline.description ?? ''
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
  }))
  applyLegacyAutoLayoutIfNeeded()
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

watch(editingSlug, loadForEdit, { immediate: true })

function addParameter() {
  parameters.value.push({ name: '', label: '', type: 'STRING' as PipelineParameterType, required: false, defaultValue: null })
}

function removeParameter(index: number) {
  parameters.value.splice(index, 1)
}

function addStep() {
  const offset = steps.value.length * 220
  steps.value.push({
    title: '',
    contentType: 'PROMPT' as PipelineStepContentType,
    promptText: '',
    assetId: null,
    referenceAssetId: null,
    positionX: offset,
    positionY: 200,
    routes: [],
    outputs: [],
    dataLinksOut: [],
  })
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
    class: selectedStepIndex.value === index ? 'pipeline-node pipeline-node-selected' : 'pipeline-node',
    data: { label: step.title || `Шаг ${index + 1}`, outputs: step.outputs },
  })),
  {
    id: END_NODE_ID,
    type: 'pipelineStep',
    position: endPosition.value,
    class: 'pipeline-node pipeline-node-end',
    data: { label: 'Конец рана', outputs: [] },
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
      description: description.value || null,
      projectScope: project.value,
      parameters: parameters.value,
      steps: normalizedSteps(),
    }
    const result = isEditing.value ? await updatePipeline(editingSlug.value!, request) : await createPipeline(request)
    await router.push({ name: 'pipeline', params: { project: project.value, slug: result.slug } })
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Pipelines"
      :title="isEditing ? 'Редактирование пайплайна' : 'Новый пайплайн'"
    />

    <ErrorState v-if="saveError" :message="saveError" />

    <div class="space-y-6">
      <section class="rounded-2xl border border-border bg-panel p-5">
        <label class="mb-1 block text-[12.5px] font-medium text-muted">Slug</label>
        <input
          v-model="slug"
          :disabled="isEditing"
          class="mb-4 w-full rounded-lg border border-border bg-elevated px-3 py-2 text-[13px] text-content disabled:opacity-60"
          placeholder="config-diff"
        />
        <label class="mb-1 block text-[12.5px] font-medium text-muted">Название</label>
        <input v-model="name" class="mb-4 w-full rounded-lg border border-border bg-elevated px-3 py-2 text-[13px] text-content" />
        <label class="mb-1 block text-[12.5px] font-medium text-muted">Описание</label>
        <textarea v-model="description" rows="2" class="w-full rounded-lg border border-border bg-elevated px-3 py-2 text-[13px] text-content" />
      </section>

      <section class="rounded-2xl border border-border bg-panel p-5">
        <div class="mb-3 flex items-center justify-between">
          <h2 class="text-[13px] font-semibold tracking-wide text-content uppercase">Параметры</h2>
          <button type="button" class="text-[12.5px] font-medium text-accent" @click="addParameter">+ Параметр</button>
        </div>
        <div v-for="(parameter, index) in parameters" :key="index" class="mb-3 flex items-center gap-2">
          <input v-model="parameter.name" placeholder="name" class="w-32 rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content" />
          <input v-model="parameter.label" placeholder="label" class="flex-1 rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content" />
          <select v-model="parameter.type" class="rounded-lg border border-border bg-elevated px-2 py-1.5 text-[12.5px] text-content">
            <option value="STRING">STRING</option>
            <option value="NUMBER">NUMBER</option>
            <option value="BOOLEAN">BOOLEAN</option>
          </select>
          <label class="flex items-center gap-1 text-[12px] text-muted">
            <input v-model="parameter.required" type="checkbox" /> required
          </label>
          <button type="button" class="text-faint hover:text-red-600" @click="removeParameter(index)">
            <AppIcon name="trash" class="size-4" />
          </button>
        </div>
      </section>

      <section class="rounded-2xl border border-border bg-panel p-5">
        <div class="mb-3 flex items-center justify-between">
          <h2 class="text-[13px] font-semibold tracking-wide text-content uppercase">Шаги</h2>
          <button type="button" class="text-[12.5px] font-medium text-accent" @click="addStep">+ Шаг</button>
        </div>
        <p class="mb-3 text-[12px] text-faint">
          Перетащите узел, чтобы разместить его; потяните от одного узла к другому, чтобы создать маршрут.
          Клик по узлу или связи открывает панель редактирования справа.
        </p>
        <div class="flex gap-4">
          <div class="h-[420px] flex-1 overflow-hidden rounded-xl border border-border bg-elevated">
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
          <aside class="w-72 shrink-0 rounded-xl border border-border bg-elevated p-4">
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
              <div class="mt-3 text-[12.5px] text-muted">
                <label class="mb-1 block">Ссылочный файл (необязательно):</label>
                <input type="file" @change="onReferenceFileChosen(selectedStepIndex, $event)" />
                <span v-if="selectedStep.referenceAssetId" class="ml-2">Загружен: asset #{{ selectedStep.referenceAssetId }}</span>
              </div>
              <div class="mt-3 text-[12.5px] text-muted">
                <div class="mb-1 flex items-center justify-between">
                  <label class="font-medium">Выходы (пины)</label>
                  <button type="button" class="text-accent" @click="addOutput(selectedStepIndex)">+ Выход</button>
                </div>
                <div v-for="(output, outputIndex) in selectedStep.outputs" :key="outputIndex" class="mb-1 flex items-center gap-2">
                  <input
                    v-model="output.name"
                    placeholder="имя, напр. summary"
                    class="flex-1 rounded-lg border border-border bg-panel px-2 py-1 text-[12px] text-content"
                  />
                  <button type="button" class="text-faint hover:text-red-600" @click="removeOutput(selectedStepIndex, outputIndex)">
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
                v-model="selectedRoute.outcomeKey"
                placeholder="пусто = маршрут по умолчанию"
                class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
              />
              <p class="mt-2 text-[11.5px] text-faint">
                Claude должен вернуть это значение как outcome в pipeline_run_step_update, чтобы run пошёл по этой
                связи. Пустое значение — маршрут по умолчанию для этого шага (используется, если outcome не
                передан или не совпал ни с одним другим маршрутом).
              </p>
            </template>
            <p v-else class="text-[12.5px] text-faint">Выберите узел или связь на канвасе.</p>
          </aside>
        </div>
      </section>

      <button
        type="button"
        :disabled="saving"
        class="rounded-lg bg-accent px-4 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover disabled:opacity-50"
        @click="save"
      >
        {{ saving ? 'Сохранение…' : 'Сохранить' }}
      </button>
    </div>
  </div>
</template>
