<script setup lang="ts">
import '@vue-flow/core/dist/style.css'
import '@vue-flow/node-resizer/dist/style.css'

import {
  ConnectionMode,
  MarkerType,
  VueFlow,
  useVueFlow,
  type Connection,
  type EdgeMouseEvent,
  type NodeDragEvent,
  type NodeMouseEvent,
} from '@vue-flow/core'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, toRef, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'

import { fetchPipeline, updatePipeline, uploadPipelineAsset } from '@/api/client'
import type { PipelineUpsertParameter } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import PipelineEndNode from '@/components/PipelineEndNode.vue'
import PipelineInputNode from '@/components/PipelineInputNode.vue'
import PipelineStepNode, { type StepNodeActions, type WiredInput } from '@/components/PipelineStepNode.vue'
import {
  BLOCK_KINDS,
  INPUT_NODE_ID,
  PARAM_PIN_COLORS,
  VARIABLE_PIN_COLOR,
  WIRE_COLORS,
  analyzeGraph,
  collectIssues,
  fromDetail,
  inputLinkSourceTitle,
  inputLinksFromDetail,
  isAncestor,
  newBoardStep,
  removeStepAt,
  startIndexFromDetail,
  stepDisplayTitle,
  stripDataToken,
  toUpsertParameterLinks,
  toUpsertSteps,
  toUpsertVariableLinks,
  variablesFromDetail,
  wouldCreateCycle,
  type BoardBlockKind,
  type BoardInputLink,
  type BoardStep,
  type BoardVariable,
} from '@/lib/pipelineBoard'

// The board always operates on an already-created pipeline - metadata (name, parameters) lives on
// the separate settings screen (PipelineBuilderView); here only the step graph is edited.
const props = defineProps<{ slug: string }>()
const slug = toRef(props, 'slug')

const { screenToFlowCoordinate, fitView, zoomIn, zoomOut, vueFlowRef, viewport } = useVueFlow()

const name = ref('')
const description = ref<string | null>(null)
// Informational only (pipelines are shared); round-tripped unchanged.
const projectScope = ref<string | null>(null)
const parameters = ref<PipelineUpsertParameter[]>([])
const steps = ref<BoardStep[]>([])
// Variables and the wires out of the input plate. A variable is not a step - it does no work, so it
// lives on the plate next to the run parameters instead of taking a card in the transition chain.
const variables = ref<BoardVariable[]>([])
const inputLinks = ref<BoardInputLink[]>([])
/** Which step the run begins at. Stated by the author on the plate, not inferred from the graph. */
const startStepIndex = ref<number | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)
const savedSnapshot = ref('')
const savedToastVisible = ref(false)

const END_NODE_ID = 'end'
const CARD_WIDTH = 320
const endPosition = ref({ x: 0, y: 0 })
// The input plate sits left of the leftmost block; its position is session-local like the end node's.
const inputPosition = ref({ x: 0, y: 0 })
// Card sizes are session-local: the API has no field for them, only for positions.
const nodeSizes = ref<Record<number, { width: number; height: number }>>({})

const selectedStepIndex = ref<number | null>(null)
const selectedEdgeId = ref<string | null>(null)
const issuesOpen = ref(false)
const contextMenu = ref<{ x: number; y: number; flowX: number; flowY: number } | null>(null)
const menuQuery = ref('')
const menuInput = ref<HTMLInputElement | null>(null)

const hint = ref<string | null>(null)
let hintTimer: ReturnType<typeof setTimeout> | null = null
function showHint(text: string) {
  hint.value = text
  if (hintTimer) clearTimeout(hintTimer)
  hintTimer = setTimeout(() => (hint.value = null), 4500)
}

// ------------------------------------------------------------------------------------------
// Load / save
// ------------------------------------------------------------------------------------------

function buildRequest() {
  return {
    slug: slug.value,
    name: name.value,
    description: description.value,
    projectScope: projectScope.value,
    parameters: parameters.value,
    steps: toUpsertSteps(steps.value),
    parameterLinks: toUpsertParameterLinks(inputLinks.value),
    variables: variables.value.map((v) => ({ name: v.name, value: v.value })),
    variableLinks: toUpsertVariableLinks(inputLinks.value),
    startStepIndex: startStepIndex.value,
  }
}

const dirty = computed(() => !loading.value && JSON.stringify(buildRequest()) !== savedSnapshot.value)

function placeInputNode() {
  if (steps.value.length === 0) {
    inputPosition.value = { x: -340, y: 40 }
    return
  }
  const minX = Math.min(...steps.value.map((s) => s.positionX))
  const ys = steps.value.filter((s) => s.positionX === minX).map((s) => s.positionY)
  inputPosition.value = { x: minX - 360, y: Math.min(...ys) }
}

function placeEndNode() {
  if (steps.value.length === 0) {
    endPosition.value = { x: CARD_WIDTH + 120, y: 40 }
    return
  }
  const maxX = Math.max(...steps.value.map((s) => s.positionX))
  const ys = steps.value.filter((s) => s.positionX === maxX).map((s) => s.positionY)
  endPosition.value = { x: maxX + CARD_WIDTH + 100, y: Math.min(...ys) + 24 }
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const pipeline = await fetchPipeline(slug.value)
    name.value = pipeline.name
    description.value = pipeline.description
    projectScope.value = pipeline.projectScope
    parameters.value = pipeline.parameters.map((p) => ({
      name: p.name,
      label: p.label,
      type: p.type,
      required: p.required,
      defaultValue: p.defaultValue,
    }))
    steps.value = fromDetail(pipeline)
    variables.value = variablesFromDetail(pipeline)
    inputLinks.value = inputLinksFromDetail(pipeline)
    startStepIndex.value = startIndexFromDetail(pipeline, steps.value)
    placeInputNode()
    placeEndNode()
    savedSnapshot.value = JSON.stringify(buildRequest())
    selectedStepIndex.value = null
    selectedEdgeId.value = null
  } catch (cause) {
    loadError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    loading.value = false
  }
}

watch(slug, load, { immediate: true })

const issues = computed(() => collectIssues({
  steps: steps.value,
  inputLinks: inputLinks.value,
  parameterNames: parameters.value.map((p) => p.name),
  variables: variables.value,
  startStepIndex: startStepIndex.value,
}))
const errorCount = computed(() => issues.value.filter((i) => i.severity === 'error').length)
const warningCount = computed(() => issues.value.filter((i) => i.severity === 'warning').length)

async function save() {
  if (saving.value) return
  if (errorCount.value > 0) {
    issuesOpen.value = true
    saveError.value = 'Сначала исправьте ошибки в списке — иначе сервер отклонит схему.'
    return
  }
  saving.value = true
  saveError.value = null
  try {
    const request = buildRequest()
    await updatePipeline(slug.value, request)
    savedSnapshot.value = JSON.stringify(request)
    savedToastVisible.value = true
    setTimeout(() => (savedToastVisible.value = false), 2000)
  } catch (cause) {
    saveError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    saving.value = false
  }
}

onBeforeRouteLeave(() => {
  if (!dirty.value) return true
  return window.confirm('На доске есть несохранённые изменения. Уйти без сохранения?')
})

function onBeforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) event.preventDefault()
}

function onKeydown(event: KeyboardEvent) {
  const target = event.target as HTMLElement | null
  const typing = !!target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT' || target.isContentEditable)
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    void save()
    return
  }
  if (event.key === 'Escape') {
    contextMenu.value = null
    issuesOpen.value = false
    selectedEdgeId.value = null
    return
  }
  if (typing) return
  if ((event.key === 'Delete' || event.key === 'Backspace') && selectedEdgeId.value) {
    event.preventDefault()
    removeEdge(selectedEdgeId.value)
  }
}

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', onBeforeUnload)
  window.removeEventListener('keydown', onKeydown)
})

// ------------------------------------------------------------------------------------------
// Step editing
// ------------------------------------------------------------------------------------------

function viewportCenter(): { x: number; y: number } {
  const rect = vueFlowRef.value?.getBoundingClientRect()
  if (!rect) return { x: 0, y: 0 }
  return screenToFlowCoordinate({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 })
}

/**
 * A block added from the palette lands to the right of the current rightmost block (same row),
 * never on top of another card; the end node steps aside if it would be covered. The first block
 * goes to the middle of the viewport. A context-menu add uses the click position as is.
 */
function nextFreePosition(): { x: number; y: number } {
  if (steps.value.length === 0) {
    const center = viewportCenter()
    return { x: center.x - CARD_WIDTH / 2, y: center.y - 160 }
  }
  const rightmost = steps.value.reduce((best, s) => (s.positionX > best.positionX ? s : best), steps.value[0])
  return { x: rightmost.positionX + CARD_WIDTH + 80, y: rightmost.positionY }
}

function addStep(kind: BoardBlockKind, at?: { x: number; y: number }) {
  const position = at ?? nextFreePosition()
  const x = Math.round(position.x)
  const y = Math.round(position.y)
  steps.value.push(newBoardStep(kind, { x, y }))
  // The first block on an empty board is the only thing the start could mean - wiring it by hand
  // would just be an error waiting to be reported.
  if (startStepIndex.value === null && steps.value.length === 1) startStepIndex.value = 0
  if (!at && endPosition.value.x < x + CARD_WIDTH + 60 && Math.abs(endPosition.value.y - y) < 420) {
    endPosition.value = { x: x + CARD_WIDTH + 100, y: y + 24 }
  }
  const id = String(steps.value.length - 1)
  selectedStepIndex.value = steps.value.length - 1
  selectedEdgeId.value = null
  contextMenu.value = null
  if (!at) {
    // Pan (keep the zoom) so the new block is on screen.
    const zoom = viewport.value.zoom
    void nextTick(() => fitView({ nodes: [id, END_NODE_ID], duration: 300, minZoom: zoom, maxZoom: zoom, padding: 0.3 }))
  }
}

function removeStep(index: number) {
  const step = steps.value[index]
  // Tokens wired INTO the removed step disappear with it; strip them from nothing. Tokens the
  // removed step wired OUT still sit in other steps' prompts - clean those up so no dangling
  // {{data:...}} is left behind.
  step.dataLinksOut.forEach((link) => {
    if (link.targetStepIndex !== null && link.targetStepIndex !== index) {
      const target = steps.value[link.targetStepIndex]
      target.promptText = stripDataToken(target.promptText, link.token)
    }
  })
  removeStepAt(steps.value, index, inputLinks.value)
  if (startStepIndex.value === index) startStepIndex.value = null
  else if (startStepIndex.value !== null && startStepIndex.value > index) startStepIndex.value--
  const sizes: Record<number, { width: number; height: number }> = {}
  Object.entries(nodeSizes.value).forEach(([key, size]) => {
    const i = Number(key)
    if (i < index) sizes[i] = size
    else if (i > index) sizes[i - 1] = size
  })
  nodeSizes.value = sizes
  selectedStepIndex.value = null
  selectedEdgeId.value = null
}

function addOutput(stepIndex: number) {
  steps.value[stepIndex].outputs.push({ name: '' })
}

function renameOutput(stepIndex: number, outputIndex: number, newName: string) {
  const step = steps.value[stepIndex]
  const oldName = step.outputs[outputIndex].name
  step.outputs[outputIndex].name = newName
  // Data links reference outputs by name - keep them pointing at the renamed pin.
  step.dataLinksOut.forEach((link) => {
    if (link.sourceOutputName === oldName) link.sourceOutputName = newName
  })
}

function removeOutput(stepIndex: number, outputIndex: number) {
  const step = steps.value[stepIndex]
  const removedName = step.outputs[outputIndex].name
  step.outputs.splice(outputIndex, 1)
  step.dataLinksOut
    .filter((link) => link.sourceOutputName === removedName)
    .forEach((link) => unwireDataLink(stepIndex, link.token))
}

function addBranch(stepIndex: number) {
  const step = steps.value[stepIndex]
  if (step.contentType === 'PARALLEL') {
    step.routes.push({ outcomeKey: null, target: null })
    return
  }
  // The default "далее" port stays last; named branches go above it.
  step.routes.splice(step.routes.length - 1, 0, { outcomeKey: '', target: null })
}

function removeBranch(stepIndex: number, routeIndex: number) {
  steps.value[stepIndex].routes.splice(routeIndex, 1)
  selectedEdgeId.value = null
}

function unwireRoute(stepIndex: number, routeIndex: number) {
  steps.value[stepIndex].routes[routeIndex].target = null
  selectedEdgeId.value = null
}

function unwireDataLink(sourceIndex: number, token: string) {
  const source = steps.value[sourceIndex]
  const link = source.dataLinksOut.find((l) => l.token === token)
  if (!link) return
  if (link.targetStepIndex !== null) {
    const target = steps.value[link.targetStepIndex]
    target.promptText = stripDataToken(target.promptText, token)
  }
  source.dataLinksOut = source.dataLinksOut.filter((l) => l.token !== token)
  selectedEdgeId.value = null
}

function unwireInputLink(token: string) {
  const link = inputLinks.value.find((l) => l.token === token)
  if (!link) return
  // The placeholder sits in whatever the wire fed: a step's instruction, or a variable's value.
  if (link.targetStepIndex !== null) {
    const target = steps.value[link.targetStepIndex]
    if (target) target.promptText = stripDataToken(target.promptText, token)
  } else if (link.targetVariableName !== null) {
    const target = variables.value.find((v) => v.name === link.targetVariableName)
    if (target) target.value = stripDataToken(target.value, token)
  }
  inputLinks.value = inputLinks.value.filter((l) => l.token !== token)
  selectedEdgeId.value = null
}

function unwireInputByToken(token: string) {
  steps.value.forEach((step, sourceIndex) => {
    if (step.dataLinksOut.some((l) => l.token === token)) unwireDataLink(sourceIndex, token)
  })
  unwireInputLink(token)
}

// ------------------------------------------------------------------------------------------
// Variables (input plate)
// ------------------------------------------------------------------------------------------

function addVariable() {
  variables.value.push({ name: '', value: '' })
}

function removeVariable(index: number) {
  const removed = variables.value[index]
  variables.value.splice(index, 1)
  // Wires out of it, and wires that fed it, go with it - placeholders included.
  inputLinks.value
    .filter((l) => (l.source.kind === 'variable' && l.source.name === removed.name) || l.targetVariableName === removed.name)
    .map((l) => l.token)
    .forEach(unwireInputLink)
}

function renameVariable(index: number, newName: string) {
  const oldName = variables.value[index].name
  variables.value[index].name = newName
  // Links address variables by name, so they follow the rename instead of dangling.
  inputLinks.value.forEach((link) => {
    if (link.source.kind === 'variable' && link.source.name === oldName) link.source = { kind: 'variable', name: newName }
    if (link.targetVariableName === oldName) link.targetVariableName = newName
  })
}

function wiredInputsFor(stepIndex: number): WiredInput[] {
  const result: WiredInput[] = []
  inputLinks.value.forEach((link) => {
    if (link.targetStepIndex === stepIndex) {
      result.push({ token: link.token, sourceStepTitle: inputLinkSourceTitle(link), sourceOutputName: link.source.name })
    }
  })
  steps.value.forEach((step, sourceIndex) => {
    step.dataLinksOut.forEach((link) => {
      if (link.targetStepIndex === stepIndex) {
        result.push({ token: link.token, sourceStepTitle: stepDisplayTitle(step, sourceIndex), sourceOutputName: link.sourceOutputName })
      }
    })
  })
  return result
}

async function onMdFileChosen(index: number, event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    const asset = await uploadPipelineAsset(file)
    steps.value[index].assetId = asset.id
  } catch (cause) {
    showHint(`Не удалось загрузить файл: ${cause instanceof Error ? cause.message : String(cause)}`)
  }
}

async function onReferenceFileChosen(index: number, event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    const asset = await uploadPipelineAsset(file)
    steps.value[index].referenceAssetId = asset.id
  } catch (cause) {
    showHint(`Не удалось загрузить файл: ${cause instanceof Error ? cause.message : String(cause)}`)
  }
}

// ------------------------------------------------------------------------------------------
// Graph -> vue-flow nodes / edges
// ------------------------------------------------------------------------------------------

const graph = computed(() => analyzeGraph(steps.value, startStepIndex.value))

function routeTargetLabel(target: BoardStep['routes'][number]['target']): string | null {
  if (target === null) return null
  if (target === 'end') return 'конец рана'
  const step = steps.value[target]
  return step ? stepDisplayTitle(step, target) : null
}

const flowInWired = computed(() => {
  const wired = new Set<number | 'end'>()
  steps.value.forEach((step) => step.routes.forEach((r) => {
    if (r.target !== null) wired.add(r.target)
  }))
  return wired
})

function actionsFor(index: number): StepNodeActions {
  return {
    remove: () => removeStep(index),
    addOutput: () => addOutput(index),
    removeOutput: (outputIndex) => removeOutput(index, outputIndex),
    renameOutput: (outputIndex, value) => renameOutput(index, outputIndex, value),
    addBranch: () => addBranch(index),
    removeBranch: (routeIndex) => removeBranch(index, routeIndex),
    unwireRoute: (routeIndex) => unwireRoute(index, routeIndex),
    unwireInput: (token) => unwireInputByToken(token),
    mdFileChosen: (event) => onMdFileChosen(index, event),
    referenceFileChosen: (event) => onReferenceFileChosen(index, event),
    resize: (size) => {
      nodeSizes.value[index] = size
    },
  }
}

const startTargetTitle = computed(() => {
  const index = startStepIndex.value
  if (index === null || !steps.value[index]) return null
  return stepDisplayTitle(steps.value[index], index)
})

const flowNodes = computed(() => [
  {
    id: INPUT_NODE_ID,
    type: 'input',
    position: inputPosition.value,
    class: 'pl-node pl-node-params',
    data: {
      parameters: parameters.value,
      variables: variables.value,
      wiredParameterNames: inputLinks.value.filter((l) => l.source.kind === 'param').map((l) => l.source.name),
      wiredVariableNames: inputLinks.value.filter((l) => l.source.kind === 'variable').map((l) => l.source.name),
      variableNamesWithInput: inputLinks.value.map((l) => l.targetVariableName).filter((n): n is string => n !== null),
      startTargetTitle: startTargetTitle.value,
      settingsTo: { name: 'pipeline-edit', params: { slug: slug.value } },
      on: { addVariable, removeVariable, renameVariable },
    },
  },
  ...steps.value.map((step, index) => {
    const size = nodeSizes.value[index]
    const { reachable } = graph.value
    return {
      id: String(index),
      type: 'step',
      position: { x: step.positionX, y: step.positionY },
      style: size ? { width: `${size.width}px`, height: `${size.height}px` } : { width: `${CARD_WIDTH}px` },
      class: ['pl-node', { 'pl-node-selected': selectedStepIndex.value === index }],
      data: {
        step,
        index,
        isStart: startStepIndex.value === index,
        unreachable: steps.value.length > 1 && startStepIndex.value !== null && !reachable.has(index),
        flowInWired: flowInWired.value.has(index),
        wiredInputs: wiredInputsFor(index),
        routeTargets: step.routes.map((r) => routeTargetLabel(r.target)),
        on: actionsFor(index),
      },
    }
  }),
  {
    id: END_NODE_ID,
    type: 'end',
    position: endPosition.value,
    class: 'pl-node pl-node-end',
    data: { wired: flowInWired.value.has('end') },
  },
])

function flowEdgeId(stepIndex: number, routeIndex: number) {
  return `flow-${stepIndex}-${routeIndex}`
}

const START_EDGE_ID = 'start'

const flowEdges = computed(() => {
  const selected = selectedEdgeId.value
  const transitions = steps.value.flatMap((step, index) =>
    step.routes.flatMap((route, routeIndex) => {
      if (route.target === null) return []
      const id = flowEdgeId(index, routeIndex)
      const isCondition = step.contentType === 'CONDITION'
      const color =
        isCondition && route.outcomeKey === 'true' ? WIRE_COLORS.trueBranch
        : isCondition && route.outcomeKey === 'false' ? WIRE_COLORS.falseBranch
        : WIRE_COLORS.flow
      const isSelected = selected === id
      return [{
        id,
        source: String(index),
        sourceHandle: `flow-out-${routeIndex}`,
        target: route.target === 'end' ? END_NODE_ID : String(route.target),
        targetHandle: 'flow-in',
        label: route.outcomeKey ?? undefined,
        class: ['pl-edge pl-edge-flow', { 'pl-edge-selected': isSelected }],
        style: { stroke: isSelected ? WIRE_COLORS.flowSelected : color, strokeWidth: isSelected ? 2.5 : 2 },
        markerEnd: { type: MarkerType.ArrowClosed, color: isSelected ? 'var(--color-accent)' : color, width: 16, height: 16 },
        labelBgPadding: [6, 3] as [number, number],
        labelBgBorderRadius: 6,
      }]
    }),
  )
  const dataWires = steps.value.flatMap((step, index) =>
    step.dataLinksOut
      .map((link) => ({ link, outputIndex: step.outputs.findIndex((o) => o.name === link.sourceOutputName) }))
      .filter(({ link, outputIndex }) => outputIndex >= 0 && link.targetStepIndex !== null)
      .map(({ link, outputIndex }) => {
        const id = `data-${link.token}`
        const isSelected = selected === id
        return {
          id,
          source: String(index),
          sourceHandle: `data-out-${outputIndex}`,
          target: String(link.targetStepIndex),
          targetHandle: 'data-in',
          class: ['pl-edge pl-edge-data', { 'pl-edge-selected': isSelected }],
          style: { stroke: isSelected ? WIRE_COLORS.flowSelected : WIRE_COLORS.data, strokeWidth: isSelected ? 2.5 : 1.75, strokeDasharray: '6 4' },
        }
      }),
  )
  // Wires out of the input plate. The source pin is a parameter row or a variable row; the target
  // is a step's data input or - when one variable is built out of another - a variable row's input.
  const inputWires = inputLinks.value.flatMap((link) => {
    const sourceIndex = link.source.kind === 'param'
      ? parameters.value.findIndex((p) => p.name === link.source.name)
      : variables.value.findIndex((v) => v.name === link.source.name)
    if (sourceIndex < 0) return []
    const targetVariableIndex = link.targetVariableName === null
      ? -1
      : variables.value.findIndex((v) => v.name === link.targetVariableName)
    if (link.targetStepIndex !== null && !steps.value[link.targetStepIndex]) return []
    if (link.targetStepIndex === null && targetVariableIndex < 0) return []
    const id = `ilink-${link.token}`
    const isSelected = selected === id
    const color = link.source.kind === 'param'
      ? PARAM_PIN_COLORS[parameters.value[sourceIndex].type]
      : VARIABLE_PIN_COLOR
    return [{
      id,
      source: INPUT_NODE_ID,
      sourceHandle: link.source.kind === 'param' ? `param-out-${sourceIndex}` : `var-out-${sourceIndex}`,
      target: link.targetStepIndex !== null ? String(link.targetStepIndex) : INPUT_NODE_ID,
      targetHandle: link.targetStepIndex !== null ? 'data-in' : `var-in-${targetVariableIndex}`,
      class: ['pl-edge pl-edge-data', { 'pl-edge-selected': isSelected }],
      style: { stroke: isSelected ? WIRE_COLORS.flowSelected : color, strokeWidth: isSelected ? 2.5 : 1.75, strokeDasharray: '6 4' },
    }]
  })
  const startWire = startStepIndex.value !== null && steps.value[startStepIndex.value]
    ? [{
        id: START_EDGE_ID,
        source: INPUT_NODE_ID,
        sourceHandle: 'start-out',
        target: String(startStepIndex.value),
        targetHandle: 'flow-in',
        label: 'старт',
        class: ['pl-edge pl-edge-flow', { 'pl-edge-selected': selected === START_EDGE_ID }],
        style: {
          stroke: selected === START_EDGE_ID ? WIRE_COLORS.flowSelected : WIRE_COLORS.flow,
          strokeWidth: selected === START_EDGE_ID ? 2.5 : 2,
        },
        markerEnd: {
          type: MarkerType.ArrowClosed,
          color: selected === START_EDGE_ID ? 'var(--color-accent)' : WIRE_COLORS.flow,
          width: 16,
          height: 16,
        },
        labelBgPadding: [6, 3] as [number, number],
        labelBgBorderRadius: 6,
      }]
    : []
  return [...startWire, ...transitions, ...dataWires, ...inputWires]
})

// ------------------------------------------------------------------------------------------
// Canvas interactions
// ------------------------------------------------------------------------------------------

function onNodeDragStop({ node }: NodeDragEvent) {
  if (node.id === END_NODE_ID) {
    endPosition.value = { x: node.position.x, y: node.position.y }
    return
  }
  if (node.id === INPUT_NODE_ID) {
    inputPosition.value = { x: node.position.x, y: node.position.y }
    return
  }
  const step = steps.value[Number(node.id)]
  step.positionX = Math.round(node.position.x)
  step.positionY = Math.round(node.position.y)
}

function onNodeClick({ node }: NodeMouseEvent) {
  selectedEdgeId.value = null
  contextMenu.value = null
  selectedStepIndex.value = node.id === END_NODE_ID || node.id === INPUT_NODE_ID ? null : Number(node.id)
}

function onEdgeClick({ edge }: EdgeMouseEvent) {
  selectedStepIndex.value = null
  contextMenu.value = null
  selectedEdgeId.value = edge.id
}

function onPaneClick() {
  selectedStepIndex.value = null
  selectedEdgeId.value = null
  contextMenu.value = null
  issuesOpen.value = false
}

function onPaneContextMenu(event: MouseEvent) {
  event.preventDefault()
  const rect = vueFlowRef.value?.getBoundingClientRect()
  if (!rect) return
  const flow = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  contextMenu.value = { x: event.clientX - rect.left, y: event.clientY - rect.top, flowX: flow.x, flowY: flow.y }
  menuQuery.value = ''
  requestAnimationFrame(() => menuInput.value?.focus())
}

const menuKinds = computed(() => {
  const q = menuQuery.value.trim().toLowerCase()
  if (!q) return BLOCK_KINDS
  return BLOCK_KINDS.filter((k) => k.label.toLowerCase().includes(q) || k.description.toLowerCase().includes(q))
})

/** Does `candidate` already take part in building `variableName`? (Walks the plate's own wires.) */
function buildsOnVariable(variableName: string, candidate: string): boolean {
  const seen = new Set<string>()
  const stack = [variableName]
  while (stack.length) {
    const current = stack.pop()!
    for (const link of inputLinks.value) {
      if (link.source.kind !== 'variable' || link.targetVariableName !== current) continue
      if (link.source.name === candidate) return true
      if (!seen.has(link.source.name)) {
        seen.add(link.source.name)
        stack.push(link.source.name)
      }
    }
  }
  return false
}

function onConnect(connection: Connection) {
  const sourceHandle = connection.sourceHandle ?? ''
  const targetHandle = connection.targetHandle ?? ''

  if (connection.source === INPUT_NODE_ID) {
    // The start port names the first step; it carries no value, so it is handled on its own.
    if (sourceHandle === 'start-out') {
      if (targetHandle !== 'flow-in' || connection.target === END_NODE_ID || connection.target === INPUT_NODE_ID) {
        showHint('Старт подключается ко входу перехода блока — порт в шапке карточки.')
        return
      }
      startStepIndex.value = Number(connection.target)
      selectedStepIndex.value = null
      selectedEdgeId.value = START_EDGE_ID
      return
    }

    const fromParameter = sourceHandle.startsWith('param-out-')
    const source = fromParameter
      ? parameters.value[Number(sourceHandle.slice('param-out-'.length))]
      : variables.value[Number(sourceHandle.slice('var-out-'.length))]
    if (!source) return
    const sourceRef = { kind: fromParameter ? ('param' as const) : ('variable' as const), name: source.name }
    if (!source.name.trim()) {
      showHint('Сначала дайте переменной имя, потом тяните от неё провод.')
      return
    }

    // Into another variable's value: that is how one variable is built out of another, with no step.
    if (connection.target === INPUT_NODE_ID) {
      if (!targetHandle.startsWith('var-in-')) {
        showHint('Значение можно подать только во вход переменной (левый пин строки).')
        return
      }
      const targetVariable = variables.value[Number(targetHandle.slice('var-in-'.length))]
      if (!targetVariable) return
      if (!fromParameter && targetVariable.name === source.name) {
        showHint('Переменную нельзя собрать из самой себя.')
        return
      }
      if (!fromParameter && buildsOnVariable(targetVariable.name, source.name)) {
        showHint('Получился бы цикл: эта переменная уже участвует в сборке источника.')
        return
      }
      if (inputLinks.value.some((l) => l.source.kind === sourceRef.kind && l.source.name === sourceRef.name && l.targetVariableName === targetVariable.name)) {
        showHint('Это значение уже подключено к этой переменной.')
        return
      }
      const token = crypto.randomUUID()
      inputLinks.value.push({ token, source: sourceRef, targetStepIndex: null, targetVariableName: targetVariable.name })
      targetVariable.value = `${targetVariable.value}{{data:${token}}}`
      selectedStepIndex.value = null
      selectedEdgeId.value = `ilink-${token}`
      return
    }

    if (targetHandle !== 'data-in' || connection.target === END_NODE_ID) {
      showHint(fromParameter
        ? 'Параметр подключается ко входу данных блока (зелёный порт).'
        : 'Переменная подключается ко входу данных блока (зелёный порт).')
      return
    }
    const targetIndex = Number(connection.target)
    const target = steps.value[targetIndex]
    if (!target) return
    if (inputLinks.value.some((l) => l.source.kind === sourceRef.kind && l.source.name === sourceRef.name && l.targetStepIndex === targetIndex)) {
      showHint('Это значение уже подключено к этому блоку.')
      return
    }
    if (target.contentType === 'CONDITION' && wiredInputsFor(targetIndex).length > 0) {
      showHint('У условия ровно один вход — сначала отвяжите текущий.')
      return
    }
    const token = crypto.randomUUID()
    inputLinks.value.push({ token, source: sourceRef, targetStepIndex: targetIndex, targetVariableName: null })
    if (target.contentType !== 'CONDITION') {
      target.promptText = `${target.promptText ?? ''}\n{{data:${token}}}`
    }
    selectedStepIndex.value = null
    selectedEdgeId.value = `ilink-${token}`
    return
  }

  const sourceIndex = Number(connection.source)
  const sourceStep = steps.value[sourceIndex]
  if (!sourceStep) return

  if (sourceHandle.startsWith('flow-out-')) {
    if (targetHandle !== 'flow-in') {
      showHint('Переход можно подключить только ко входу перехода (порт в шапке блока).')
      return
    }
    const routeIndex = Number(sourceHandle.slice('flow-out-'.length))
    const route = sourceStep.routes[routeIndex]
    if (!route) return
    if (connection.target === END_NODE_ID) {
      route.target = 'end'
    } else {
      const targetIndex = Number(connection.target)
      if (targetIndex === sourceIndex) {
        showHint('Блок нельзя соединить с самим собой.')
        return
      }
      if (wouldCreateCycle(steps.value, sourceIndex, targetIndex) && steps.value[targetIndex].contentType !== 'LOOP') {
        showHint('Обратный переход должен вести в блок «Цикл» — у него есть ограничение числа итераций.')
        return
      }
      route.target = targetIndex
    }
    selectedStepIndex.value = null
    selectedEdgeId.value = flowEdgeId(sourceIndex, routeIndex)
    return
  }

  if (sourceHandle.startsWith('data-out-')) {
    if (targetHandle !== 'data-in') {
      showHint('Выход данных подключается ко входу данных (зелёный порт).')
      return
    }
    if (connection.target === END_NODE_ID) return
    const targetIndex = Number(connection.target)
    if (targetIndex === sourceIndex) {
      showHint('Блок нельзя соединить с самим собой.')
      return
    }
    const outputIndex = Number(sourceHandle.slice('data-out-'.length))
    const sourceOutputName = sourceStep.outputs[outputIndex]?.name ?? ''
    if (!sourceOutputName.trim()) {
      showHint('Сначала дайте выходу имя, потом тяните от него провод.')
      return
    }
    const target = steps.value[targetIndex]
    if (sourceStep.dataLinksOut.some((l) => l.sourceOutputName === sourceOutputName && l.targetStepIndex === targetIndex)) {
      showHint('Этот выход уже подключён к этому блоку.')
      return
    }
    if (target.contentType === 'CONDITION' && wiredInputsFor(targetIndex).length > 0) {
      showHint('У условия ровно один вход — сначала отвяжите текущий.')
      return
    }
    if (!isAncestor(steps.value, sourceIndex, targetIndex)) {
      showHint('Данные идут только по ходу выполнения: соедините блоки переходом, иначе схема не сохранится.')
    }
    const token = crypto.randomUUID()
    sourceStep.dataLinksOut.push({ token, sourceOutputName, targetStepIndex: targetIndex })
    if (target.contentType !== 'CONDITION') {
      target.promptText = `${target.promptText ?? ''}\n{{data:${token}}}`
    }
    selectedStepIndex.value = null
    selectedEdgeId.value = `data-${token}`
  }
}

function removeEdge(id: string) {
  if (id === START_EDGE_ID) {
    startStepIndex.value = null
    selectedEdgeId.value = null
  } else if (id.startsWith('flow-')) {
    const [, stepIndex, routeIndex] = id.split('-').map(Number)
    unwireRoute(stepIndex, routeIndex)
  } else if (id.startsWith('data-')) {
    unwireInputByToken(id.slice('data-'.length))
  } else if (id.startsWith('ilink-')) {
    unwireInputLink(id.slice('ilink-'.length))
  }
}

const selectedEdgeSummary = computed(() => {
  const id = selectedEdgeId.value
  if (!id) return null
  if (id === START_EDGE_ID) {
    return {
      kind: 'flow' as const,
      title: 'Старт',
      text: `Вход пайплайна → ${startTargetTitle.value ?? '—'}`,
      detail: 'С этого блока начинается каждый запуск. Переподключите провод, чтобы сменить первый шаг.',
    }
  }
  if (id.startsWith('flow-')) {
    const [, stepIndex, routeIndex] = id.split('-').map(Number)
    const step = steps.value[stepIndex]
    const route = step?.routes[routeIndex]
    if (!step || !route) return null
    const key = route.outcomeKey ?? 'далее'
    return {
      kind: 'flow' as const,
      title: 'Переход',
      text: `${stepDisplayTitle(step, stepIndex)} → ${routeTargetLabel(route.target) ?? '—'}`,
      detail: step.contentType === 'CONDITION'
        ? `Ветка «${key}» условия.`
        : route.outcomeKey === null
          ? 'Срабатывает, если Claude не вернул outcome или он не совпал ни с одной веткой.'
          : `Срабатывает, когда Claude возвращает outcome «${key}» в pipeline_run_step_update.`,
    }
  }
  if (id.startsWith('ilink-')) {
    const token = id.slice('ilink-'.length)
    const link = inputLinks.value.find((l) => l.token === token)
    if (!link) return null
    const what = link.source.kind === 'param' ? 'параметра' : 'переменной'
    if (link.targetVariableName !== null) {
      return {
        kind: 'data' as const,
        title: `Провод ${what}`,
        text: `${inputLinkSourceTitle(link)} · ${link.source.name} → переменная ${link.targetVariableName}`,
        detail: `В значение переменной вставлен маркер {{data:${token.slice(0, 8)}…}} — он раскроется при запуске, до первого шага.`,
      }
    }
    const target = link.targetStepIndex !== null ? steps.value[link.targetStepIndex] : undefined
    if (!target || link.targetStepIndex === null) return null
    return {
      kind: 'data' as const,
      title: `Провод ${what}`,
      text: `${inputLinkSourceTitle(link)} · ${link.source.name} → ${stepDisplayTitle(target, link.targetStepIndex)}`,
      detail: target.contentType === 'CONDITION'
        ? 'Условие сравнит это значение с заданным.'
        : `В инструкцию вставлен маркер {{data:${token.slice(0, 8)}…}} — при запуске он заменится значением.`,
    }
  }
  const token = id.slice('data-'.length)
  for (const [sourceIndex, step] of steps.value.entries()) {
    const link = step.dataLinksOut.find((l) => l.token === token)
    if (link && link.targetStepIndex !== null) {
      const target = steps.value[link.targetStepIndex]
      return {
        kind: 'data' as const,
        title: 'Провод данных',
        text: `${stepDisplayTitle(step, sourceIndex)} · ${link.sourceOutputName} → ${stepDisplayTitle(target, link.targetStepIndex)}`,
        detail: target.contentType === 'CONDITION'
          ? 'Это значение условие сравнивает с заданным.'
          : `В инструкцию вставлен маркер {{data:${token.slice(0, 8)}…}} — при запуске он заменится значением выхода.`,
      }
    }
  }
  return null
})

function focusIssue(stepIndex: number | null) {
  issuesOpen.value = false
  if (stepIndex === null) return
  selectedStepIndex.value = stepIndex
  selectedEdgeId.value = null
  void fitView({ nodes: [String(stepIndex)], duration: 350, maxZoom: 1, padding: 0.4 })
}
</script>

<template>
  <div class="pl-board">
    <header class="pl-topbar">
      <div class="pl-topbar-left">
        <RouterLink :to="{ name: 'pipeline', params: { slug } }" class="pl-topbar-back" title="К пайплайну">
          <AppIcon name="arrowLeft" class="size-4" />
        </RouterLink>
        <div class="min-w-0">
          <p class="pl-topbar-title">{{ name || slug }}</p>
          <p class="pl-topbar-sub">
            <span>{{ slug }}</span>
            <span v-if="dirty" class="pl-dirty">не сохранено</span>
            <span v-else-if="!loading" class="pl-clean">сохранено</span>
          </p>
        </div>
      </div>

      <div class="pl-palette" aria-label="Добавить блок">
        <span class="pl-palette-caption">Добавить</span>
        <button
          v-for="kind in BLOCK_KINDS"
          :key="kind.kind"
          type="button"
          class="pl-palette-item"
          :style="{ '--kind': kind.color }"
          :title="kind.description"
          :disabled="loading"
          @click="addStep(kind.kind)"
        >
          <span class="pl-palette-tile"><AppIcon :name="kind.icon" class="size-3.5" /></span>
          <span class="pl-palette-label">{{ kind.label }}</span>
        </button>
      </div>

      <div class="pl-topbar-right">
        <div class="relative">
          <button
            v-if="issues.length"
            type="button"
            class="pl-issues-btn"
            :class="errorCount ? 'pl-issues-btn-error' : 'pl-issues-btn-warn'"
            @click="issuesOpen = !issuesOpen"
          >
            <AppIcon name="warning" class="size-3.5" />
            <span v-if="errorCount">{{ errorCount }} ошиб.</span>
            <span v-if="warningCount">{{ warningCount }} предупр.</span>
          </button>
          <div v-if="issuesOpen && issues.length" class="pl-issues-pop">
            <button
              v-for="(issue, i) in issues"
              :key="i"
              type="button"
              class="pl-issue"
              :class="`pl-issue-${issue.severity}`"
              @click="focusIssue(issue.stepIndex)"
            >
              <span class="pl-issue-dot" />
              <span>{{ issue.text }}</span>
            </button>
          </div>
        </div>
        <RouterLink :to="{ name: 'pipeline-edit', params: { slug } }" class="pl-btn">
          <AppIcon name="cog" class="size-3.5" />
          Параметры
        </RouterLink>
        <button type="button" :disabled="saving || loading" class="pl-btn pl-btn-primary" title="⌘S / Ctrl+S" @click="save">
          {{ saving ? 'Сохраняю…' : 'Сохранить' }}
        </button>
      </div>
    </header>

    <ErrorState v-if="loadError || saveError" :message="(loadError || saveError)!" class="m-3 shrink-0" />

    <div v-if="!loading" class="pl-canvas-wrap">
      <VueFlow
        :nodes="flowNodes"
        :edges="flowEdges"
        :node-types="{ step: PipelineStepNode, end: PipelineEndNode, input: PipelineInputNode }"
        :connection-mode="ConnectionMode.Strict"
        :connection-line-style="{ stroke: 'var(--color-accent)', strokeWidth: 2 }"
        :delete-key-code="null"
        :min-zoom="0.2"
        :max-zoom="1.6"
        :snap-to-grid="true"
        :snap-grid="[8, 8]"
        fit-view-on-init
        class="pl-flow"
        @node-drag-stop="onNodeDragStop"
        @node-click="onNodeClick"
        @edge-click="onEdgeClick"
        @pane-click="onPaneClick"
        @pane-context-menu="onPaneContextMenu"
        @connect="onConnect"
      >
        <svg class="pl-bg">
          <pattern
            id="pl-dots"
            :x="viewport.x"
            :y="viewport.y"
            :width="24 * viewport.zoom"
            :height="24 * viewport.zoom"
            patternUnits="userSpaceOnUse"
          >
            <circle :cx="viewport.zoom" :cy="viewport.zoom" :r="Math.max(0.6, viewport.zoom)" fill="currentColor" />
          </pattern>
          <rect width="100%" height="100%" fill="url(#pl-dots)" />
        </svg>
      </VueFlow>

      <div class="pl-controls">
        <button type="button" class="pl-control" title="Приблизить" @click="zoomIn()"><AppIcon name="plus" class="size-3.5" /></button>
        <button type="button" class="pl-control" title="Отдалить" @click="zoomOut()"><AppIcon name="minus" class="size-3.5" /></button>
        <button type="button" class="pl-control" title="Показать всё" @click="fitView({ duration: 300, padding: 0.2 })"><AppIcon name="fit" class="size-3.5" /></button>
      </div>

      <div v-if="steps.length === 0" class="pl-empty">
        <p class="pl-empty-title">Доска пуста</p>
        <p>Добавьте первый блок из палитры слева или правым кликом по холсту.</p>
      </div>

      <div class="pl-legend" aria-hidden="true">
        <span><i class="pl-legend-flow" />переход</span>
        <span><i class="pl-legend-data" />данные</span>
        <span><i class="pl-legend-param" />параметр</span>
        <span><i class="pl-legend-var" />переменная</span>
      </div>

      <div v-if="hint" class="pl-toast">{{ hint }}</div>
      <div v-else-if="savedToastVisible" class="pl-toast pl-toast-ok"><AppIcon name="check" class="size-3.5" />Сохранено</div>

      <div v-if="selectedEdgeSummary" class="pl-edge-card">
        <div class="pl-edge-card-head">
          <span class="pl-edge-card-title">{{ selectedEdgeSummary.title }}</span>
          <button type="button" class="pl-btn pl-btn-danger" @click="removeEdge(selectedEdgeId!)">
            <AppIcon name="unlink" class="size-3.5" />
            Отсоединить
          </button>
        </div>
        <p class="pl-edge-card-text">{{ selectedEdgeSummary.text }}</p>
        <p class="pl-edge-card-detail">{{ selectedEdgeSummary.detail }}</p>
        <p class="pl-edge-card-key">Delete — отсоединить, Esc — снять выделение</p>
      </div>

      <div v-if="contextMenu" class="pl-menu" :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }" @click.stop>
        <input ref="menuInput" v-model="menuQuery" placeholder="Какой блок добавить?" class="pl-menu-search" @keydown.enter="menuKinds[0] && addStep(menuKinds[0].kind, { x: contextMenu.flowX, y: contextMenu.flowY })" />
        <button
          v-for="kind in menuKinds"
          :key="kind.kind"
          type="button"
          class="pl-menu-item"
          :style="{ '--kind': kind.color }"
          @click="addStep(kind.kind, { x: contextMenu!.flowX, y: contextMenu!.flowY })"
        >
          <span class="pl-palette-tile"><AppIcon :name="kind.icon" class="size-3.5" /></span>
          <span class="pl-menu-item-text">
            <span class="pl-menu-item-label">{{ kind.label }}</span>
            <span class="pl-menu-item-desc">{{ kind.description }}</span>
          </span>
        </button>
        <p v-if="menuKinds.length === 0" class="pl-menu-empty">Ничего не найдено</p>
      </div>
    </div>
  </div>
</template>
