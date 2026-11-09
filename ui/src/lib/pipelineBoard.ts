import type {
  PipelineConditionMode,
  PipelineConditionOperator,
  PipelineDelegationHint,
  PipelineDetail,
  PipelineParameterType,
  PipelineStepContentType,
  PipelineUpsertDataLink,
  PipelineUpsertOutput,
  PipelineUpsertStep,
  PipelineWatchAspect,
  PipelineWatchSource,
} from '@/api/types'

/** The kinds of block an author can actually put on the board (`VARIABLE` is not one - see below). */
export type BoardBlockKind = Exclude<PipelineStepContentType, 'VARIABLE'>

/**
 * Board-local model of a pipeline step.
 *
 * The API's route model only knows about routes that lead somewhere (`targetStepIndex: number`
 * = a step, `null` = end of run). The board needs one more state - a transition port that exists
 * on the card but is not wired yet - so every card can show its ports up front instead of
 * conjuring a "default route" the moment the user drags a wire. `BoardRoute.target` therefore has
 * three states: a step index, `'end'`, or `null` for "declared but unwired". Unwired ports are
 * simply dropped when converting back to the API shape (`toUpsertSteps`).
 */
export type BoardRouteTarget = number | 'end' | null

export interface BoardRoute {
  outcomeKey: string | null
  target: BoardRouteTarget
}

export interface BoardStep {
  title: string
  contentType: PipelineStepContentType
  promptText: string | null
  assetId: number | null
  referenceAssetId: number | null
  positionX: number
  positionY: number
  routes: BoardRoute[]
  outputs: PipelineUpsertOutput[]
  dataLinksOut: PipelineUpsertDataLink[]
  conditionOperator: PipelineConditionOperator | null
  conditionValue: string | null
  conditionMode: PipelineConditionMode
  maxIterations: number | null
  watchSource: PipelineWatchSource | null
  watchAspect: PipelineWatchAspect | null
  checkIntervalSeconds: number | null
  watchTimeoutSeconds: number | null
  /** Index of the block that opens the region this block closes (LOOP_END, ORCHESTRATOR_END). */
  regionStartStepIndex: number | null
  delegationHint: PipelineDelegationHint | null
}

/** A named constant on the input plate: the author writes the value, nobody types it at run time. */
export interface BoardVariable {
  name: string
  value: string
}

/**
 * A wire out of the input plate - from a run parameter or from a variable - into a step's data
 * input, or into another variable's value. Exactly one of the two targets is set.
 *
 * Both sources share one model because they share everything that matters to the board: the value
 * is known before the first step runs, the wire carries a `{{data:token}}` placeholder, and it can
 * land either on a step or on a variable being assembled out of other values.
 */
export interface BoardInputLink {
  token: string
  source: { kind: 'param' | 'variable'; name: string }
  targetStepIndex: number | null
  targetVariableName: string | null
}

/** Parameter pins are colored by type (the spec's PIN_COLORS); step outputs stay untyped/emerald. */
export const PARAM_PIN_COLORS: Record<PipelineParameterType, string> = {
  STRING: '#eab308',
  NUMBER: '#3b82f6',
  BOOLEAN: '#a855f7',
}

/** Variables have no type, so one color - the teal the variable step used to carry. */
export const VARIABLE_PIN_COLOR = '#0d9488'

export const INPUT_NODE_ID = 'input'
export const PARAMS_SOURCE_TITLE = 'Параметры'
export const VARIABLES_SOURCE_TITLE = 'Переменные'

export function inputLinkSourceTitle(link: BoardInputLink): string {
  return link.source.kind === 'param' ? PARAMS_SOURCE_TITLE : VARIABLES_SOURCE_TITLE
}

export interface BlockKindMeta {
  kind: BoardBlockKind
  label: string
  description: string
  icon: string
  /** CSS color for the kind's accent (icon tile, header stripe). */
  color: string
}

/**
 * Blocks the author can add. There is no «Переменная» here any more: a variable does no work, so it
 * is a block on the input plate (see {@link BoardVariable}) instead of a step in the chain - which
 * is what used to force authors to stand variables in a line just to have a few of them.
 */
export const BLOCK_KINDS: BlockKindMeta[] = [
  { kind: 'PROMPT', label: 'Промпт', description: 'Инструкция для Claude', icon: 'sparkles', color: 'var(--color-accent)' },
  { kind: 'MD_FILE', label: 'MD-файл', description: 'Инструкция из загруженного файла', icon: 'document', color: '#2563eb' },
  { kind: 'CONDITION', label: 'Условие', description: 'Сравнивает значение и выбирает ветку', icon: 'branch', color: '#d97706' },
  { kind: 'PARALLEL', label: 'Параллельно', description: 'Запускает все ветки одновременно, каждую — суб-агентом', icon: 'fanout', color: '#0ea5e9' },
  { kind: 'JOIN', label: 'Ожидать все', description: 'Продолжает, когда все ветки завершились', icon: 'join', color: '#6366f1' },
  { kind: 'LOOP', label: 'Цикл', description: 'Повторяет тело заданное число раз; тело замыкает блок «Конец цикла»', icon: 'refresh', color: '#c026d3' },
  { kind: 'LOOP_END', label: 'Конец цикла', description: 'Замыкает тело цикла: поток сам возвращается в начало', icon: 'refresh', color: '#c026d3' },
  { kind: 'PROMPT_LOOP', label: 'Цикл с промптом', description: 'Повторяет один промпт заданное число раз сам в себе', icon: 'refresh', color: '#a21caf' },
  { kind: 'ORCHESTRATOR', label: 'Оркестратор', description: 'Дорогая модель раздаёт шаги региона дешёвым и принимает работу', icon: 'fanout', color: '#ea580c' },
  { kind: 'ORCHESTRATOR_END', label: 'Приёмка', description: 'Конец региона оркестрации: дальше работает сам ведущий агент', icon: 'join', color: '#ea580c' },
  { kind: 'WATCHER', label: 'Наблюдатель', description: 'Ждёт внешнее событие и ветвится по результату', icon: 'eye', color: '#0891b2' },
]

/** Blocks that only mark a region boundary: no prompt, no data, at most one transition. */
export const REGION_MARKERS: PipelineStepContentType[] = ['LOOP_END', 'ORCHESTRATOR', 'ORCHESTRATOR_END']

/** The kind a region marker must point at, for the "which block does this close?" picker. */
export const REGION_START_KIND: Partial<Record<PipelineStepContentType, PipelineStepContentType>> = {
  LOOP_END: 'LOOP',
  ORCHESTRATOR_END: 'ORCHESTRATOR',
}

export const DELEGATION_HINTS: { value: PipelineDelegationHint | null; label: string }[] = [
  { value: null, label: 'решает директор' },
  { value: 'CHEAPEST', label: 'подешевле' },
  { value: 'BALANCED', label: 'средняя' },
  { value: 'SELF', label: 'только директор' },
]

/**
 * A watcher's outcome ports are fixed by what it watches - the author never names them. Mirrors
 * PipelineService.watcherOutcomeKeys on the server, which is the authority; keep the two in step.
 */
export function watcherOutcomeKeys(
  source: PipelineWatchSource | null,
  aspect: PipelineWatchAspect | null,
): string[] {
  if (source === 'COMMAND') return ['success', 'timeout']
  return aspect === 'REVIEW' ? ['approved', 'comments', 'timeout'] : ['success', 'failed', 'timeout']
}

/**
 * Port labels depend on what is being watched, not just on the outcome key: `success` means
 * "the build passed" for a GitLab build watcher and "it happened" for a probe command, and showing
 * the generic wording next to «сборка упала» reads as two different vocabularies on one axis.
 */
export function watcherPortLabel(
  source: PipelineWatchSource | null,
  aspect: PipelineWatchAspect | null,
  outcomeKey: string | null,
): string {
  if (outcomeKey === 'timeout') return 'не дождались'
  if (source === 'GITLAB_MR' && aspect === 'PIPELINE') {
    if (outcomeKey === 'success') return 'сборка прошла'
    if (outcomeKey === 'failed') return 'сборка упала'
  }
  if (source === 'GITLAB_MR' && aspect === 'REVIEW') {
    if (outcomeKey === 'approved') return 'апрув'
    if (outcomeKey === 'comments') return 'есть замечания'
  }
  if (outcomeKey === 'success') return 'событие наступило'
  return outcomeKey ?? DEFAULT_ROUTE_LABEL
}

/** How a kind is presented. The map below is keyed by the kind, so the value does not repeat it. */
export type StepKindMeta = Omit<BlockKindMeta, 'kind'>

/**
 * Presentation for every kind a card can report: the palette's, plus `VARIABLE` - which no new
 * pipeline can contain, but runs recorded before V24 do, and their step list still has to render.
 */
export const BLOCK_KIND_BY_TYPE: Record<PipelineStepContentType, StepKindMeta> = Object.fromEntries([
  ...BLOCK_KINDS.map((k) => [k.kind, k]),
  ['VARIABLE', {
    label: 'Переменная',
    description: 'Значение из прошлых запусков — теперь переменные живут на плашке входа',
    icon: 'variable',
    color: VARIABLE_PIN_COLOR,
  } satisfies StepKindMeta],
]) as Record<PipelineStepContentType, StepKindMeta>

/** Wire/pin colors shared by the board and the read-only views. */
export const WIRE_COLORS = {
  flow: '#8b8fa3',
  flowSelected: 'var(--color-accent)',
  data: '#10b981',
  trueBranch: '#16a34a',
  falseBranch: '#dc2626',
}

export const DEFAULT_ROUTE_LABEL = 'далее'

/** A GITLAB_MR watcher's target must name a merge request - the project and iid come from it. */
export const MERGE_REQUEST_URL = /\/merge_requests\/\d+/

/** `{{data:token}}` / `{{param}}` - a target whose real value only exists at run time. */
export const TEMPLATE_TOKEN = /\{\{[^}]+\}\}/

/** Can this kind of step have named (outcome-keyed) transition ports added by the author? */
export function supportsNamedBranches(kind: PipelineStepContentType): boolean {
  return kind === 'PROMPT' || kind === 'MD_FILE' || kind === 'PROMPT_LOOP'
}

/** Blocks that carry no data at all: pure control flow or region boundaries. */
const CARRIES_NO_DATA: PipelineStepContentType[] = ['PARALLEL', 'JOIN', 'LOOP', 'LOOP_END', 'ORCHESTRATOR', 'ORCHESTRATOR_END']

/** Does this kind of step take a data input wire? */
export function acceptsDataInput(kind: PipelineStepContentType): boolean {
  return !CARRIES_NO_DATA.includes(kind)
}

/** Does this kind of step publish data outputs at all? */
export function hasOutputs(kind: PipelineStepContentType): boolean {
  return kind !== 'CONDITION' && !CARRIES_NO_DATA.includes(kind)
}

/** Can the author add/remove transition ports? (PROMPT/MD_FILE add keyed branches, PARALLEL adds unkeyed ones.) */
export function supportsAddingPorts(kind: PipelineStepContentType): boolean {
  return supportsNamedBranches(kind) || kind === 'PARALLEL'
}

/** Can the author add/remove data outputs on this kind of step? */
export function hasEditableOutputs(kind: PipelineStepContentType): boolean {
  return kind === 'PROMPT' || kind === 'MD_FILE' || kind === 'WATCHER' || kind === 'PROMPT_LOOP'
}

export function stepDisplayTitle(step: { title: string }, index: number): string {
  return step.title.trim() || `Шаг ${index + 1}`
}

export function newBoardStep(kind: BoardBlockKind, position: { x: number; y: number }): BoardStep {
  const step: BoardStep = {
    title: '',
    contentType: kind,
    promptText: '',
    assetId: null,
    referenceAssetId: null,
    positionX: position.x,
    positionY: position.y,
    routes: [],
    outputs: [],
    dataLinksOut: [],
    conditionOperator: null,
    conditionValue: null,
    conditionMode: 'VALUE',
    maxIterations: kind === 'LOOP' || kind === 'PROMPT_LOOP' ? 5 : kind === 'ORCHESTRATOR' ? 2 : null,
    watchSource: kind === 'WATCHER' ? 'GITLAB_MR' : null,
    watchAspect: kind === 'WATCHER' ? 'PIPELINE' : null,
    checkIntervalSeconds: kind === 'WATCHER' ? 60 : null,
    watchTimeoutSeconds: kind === 'WATCHER' ? 3600 : null,
    regionStartStepIndex: null,
    delegationHint: null,
  }
  if (kind === 'CONDITION') {
    step.conditionOperator = 'EQUALS'
    step.conditionValue = ''
  }
  ensureFixedPorts(step)
  return step
}

/**
 * Every card shows the transition ports its kind requires, wired or not:
 * - PROMPT / MD_FILE: exactly one default port ("далее"), kept last.
 * - CONDITION: exactly `true` and `false`; LOOP: body and `done`.
 * Named ports on PROMPT / MD_FILE are author-added and left untouched here.
 */
export function ensureFixedPorts(step: BoardStep): void {
  if (step.contentType === 'CONDITION') {
    const byKey = new Map(step.routes.map((r) => [r.outcomeKey, r]))
    step.routes = [
      byKey.get('true') ?? { outcomeKey: 'true', target: null },
      byKey.get('false') ?? { outcomeKey: 'false', target: null },
    ]
    return
  }
  if (step.contentType === 'LOOP') {
    step.routes = [
      step.routes.find((r) => r.outcomeKey === null) ?? { outcomeKey: null, target: null },
      step.routes.find((r) => r.outcomeKey === 'done') ?? { outcomeKey: 'done', target: null },
    ]
    return
  }
  if (step.contentType === 'LOOP_END') {
    // Its only transition is the implicit return to its loop, which the server walks.
    step.routes = []
    return
  }
  if (step.contentType === 'WATCHER') {
    // The port set follows the source/aspect; a port whose key survives the change keeps its wire,
    // the rest (and their wires) go, and newly appearing keys start unwired.
    const byKey = new Map(step.routes.map((r) => [r.outcomeKey, r]))
    step.routes = watcherOutcomeKeys(step.watchSource, step.watchAspect)
      .map((key) => byKey.get(key) ?? { outcomeKey: key, target: null })
    return
  }
  if (step.contentType === 'PARALLEL') {
    // Every port is an unkeyed branch that always fires; start with two so the fan-out is visible.
    const branches = step.routes.filter((r) => r.outcomeKey === null)
    step.routes = branches.length >= 2 ? branches : [...branches, ...Array.from({ length: 2 - branches.length }, () => ({ outcomeKey: null, target: null }) as BoardRoute)]
    return
  }
  const named = step.routes.filter((r) => r.outcomeKey !== null)
  const defaults = step.routes.filter((r) => r.outcomeKey === null)
  const defaultRoute = defaults[0] ?? { outcomeKey: null, target: null }
  const singlePortKinds: PipelineStepContentType[] = ['JOIN', 'ORCHESTRATOR', 'ORCHESTRATOR_END']
  step.routes = singlePortKinds.includes(step.contentType) ? [defaultRoute] : [...named, defaultRoute]
}

export function fromDetail(pipeline: PipelineDetail): BoardStep[] {
  const steps: BoardStep[] = pipeline.steps.map((s) => ({
    title: s.title,
    contentType: s.contentType,
    promptText: s.promptText,
    assetId: s.assetId,
    referenceAssetId: s.referenceAssetId,
    positionX: s.positionX,
    positionY: s.positionY,
    routes: s.routes.map((r) => ({
      outcomeKey: r.outcomeKey,
      target: r.targetStepOrderIndex === null ? 'end' : r.targetStepOrderIndex,
    })),
    outputs: s.outputs.map((o) => ({ name: o.name })),
    dataLinksOut: s.dataLinksOut.map((l) => ({
      token: l.token,
      sourceOutputName: l.sourceOutputName,
      targetStepIndex: l.targetStepOrderIndex,
    })),
    conditionOperator: s.conditionOperator,
    conditionValue: s.conditionValue,
    conditionMode: s.conditionMode,
    maxIterations: s.maxIterations,
    watchSource: s.watchSource,
    watchAspect: s.watchAspect,
    checkIntervalSeconds: s.checkIntervalSeconds,
    watchTimeoutSeconds: s.watchTimeoutSeconds,
    regionStartStepIndex: s.regionStartStepOrderIndex,
    delegationHint: s.delegationHint,
  }))
  materializeLegacyChain(steps)
  steps.forEach(ensureFixedPorts)
  applyLegacyAutoLayoutIfNeeded(steps)
  return steps
}

/**
 * A pipeline saved before branching existed has no routes anywhere and the engine runs it as an
 * implicit orderIndex -> orderIndex+1 chain. On the board that chain becomes real, visible
 * wires, so nothing about the execution order is hidden; saving persists them as ordinary default
 * routes, which the engine treats identically.
 */
export function materializeLegacyChain(steps: BoardStep[]): void {
  if (steps.length === 0 || steps.some((s) => s.routes.length > 0)) return
  steps.forEach((step, index) => {
    if (step.contentType === 'CONDITION' || step.contentType === 'LOOP_END') return
    step.routes = [{ outcomeKey: null, target: index + 1 < steps.length ? index + 1 : 'end' }]
  })
}

export function applyLegacyAutoLayoutIfNeeded(steps: BoardStep[]): void {
  const allAtOrigin = steps.length > 0 && steps.every((s) => s.positionX === 0 && s.positionY === 0)
  if (!allAtOrigin) return
  steps.forEach((step, index) => {
    step.positionX = index * 380
    step.positionY = 0
  })
}

export function variablesFromDetail(pipeline: PipelineDetail): BoardVariable[] {
  return pipeline.variables.map((v) => ({ name: v.name, value: v.value ?? '' }))
}

export function inputLinksFromDetail(pipeline: PipelineDetail): BoardInputLink[] {
  return [
    ...pipeline.parameterLinks.map((l) => ({
      token: l.token,
      source: { kind: 'param' as const, name: l.parameterName },
      targetStepIndex: l.targetStepOrderIndex,
      targetVariableName: l.targetVariableName,
    })),
    ...pipeline.variableLinks.map((l) => ({
      token: l.token,
      source: { kind: 'variable' as const, name: l.variableName },
      targetStepIndex: l.targetStepOrderIndex,
      targetVariableName: l.targetVariableName,
    })),
  ]
}

/**
 * A pipeline saved before V24 has no start of its own; the board shows the same step the engine
 * would have inferred, so the author sees where their pipeline actually begins and the next save
 * writes it down.
 */
export function startIndexFromDetail(pipeline: PipelineDetail, steps: BoardStep[]): number | null {
  if (pipeline.startStepOrderIndex !== null) return pipeline.startStepOrderIndex
  if (steps.length === 0) return null
  const { roots } = analyzeGraph(steps, null)
  return roots.length > 0 ? roots[0] : 0
}

export function toUpsertParameterLinks(links: BoardInputLink[]) {
  return links
    .filter((l) => l.source.kind === 'param')
    .map((l) => ({ token: l.token, parameterName: l.source.name, targetStepIndex: l.targetStepIndex, targetVariableName: l.targetVariableName }))
}

export function toUpsertVariableLinks(links: BoardInputLink[]) {
  return links
    .filter((l) => l.source.kind === 'variable')
    .map((l) => ({ token: l.token, variableName: l.source.name, targetStepIndex: l.targetStepIndex, targetVariableName: l.targetVariableName }))
}

export function toUpsertSteps(steps: BoardStep[]): PipelineUpsertStep[] {
  return steps.map((step) => {
    const declaredOutputs = new Set(step.outputs.map((o) => o.name))
    return {
      title: step.title,
      contentType: step.contentType,
      promptText: step.promptText,
      assetId: step.assetId,
      referenceAssetId: step.referenceAssetId,
      positionX: step.positionX,
      positionY: step.positionY,
      routes: step.routes
        // Unwired ports are board-only, except fixed branches that the backend requires even when
        // they end the run: CONDITION's true/false and LOOP's body/done.
        .filter((r) => r.target !== null || step.contentType === 'CONDITION' || step.contentType === 'LOOP'
          || step.contentType === 'WATCHER' || step.contentType === 'ORCHESTRATOR'
          || step.contentType === 'ORCHESTRATOR_END')
        .map((r) => ({
          outcomeKey: r.outcomeKey && r.outcomeKey.trim() !== '' ? r.outcomeKey.trim() : null,
          targetStepIndex: r.target === 'end' || r.target === null ? null : r.target,
        })),
      outputs: step.outputs.map((o) => ({ name: o.name.trim() })),
      dataLinksOut: step.dataLinksOut.filter((link) => declaredOutputs.has(link.sourceOutputName)),
      conditionOperator: step.conditionOperator,
      conditionValue: step.conditionValue,
      conditionMode: step.conditionMode,
      maxIterations: step.maxIterations,
      watchSource: step.watchSource,
      watchAspect: step.watchAspect,
      checkIntervalSeconds: step.checkIntervalSeconds,
      watchTimeoutSeconds: step.watchTimeoutSeconds,
      regionStartStepIndex: step.regionStartStepIndex,
      delegationHint: step.delegationHint,
    }
  })
}

/** Remove step `index` and re-point every route / data link that referenced steps by index. */
export function removeStepAt(steps: BoardStep[], index: number, inputLinks: BoardInputLink[] = []): void {
  steps.splice(index, 1)
  const survivingInputLinks = inputLinks
    .filter((l) => l.targetStepIndex !== index)
    .map((l) => ({
      ...l,
      targetStepIndex: l.targetStepIndex !== null && l.targetStepIndex > index ? l.targetStepIndex - 1 : l.targetStepIndex,
    }))
  inputLinks.splice(0, inputLinks.length, ...survivingInputLinks)
  steps.forEach((step) => {
    step.routes = step.routes.map((r) => {
      if (r.target === index) return { ...r, target: null }
      if (typeof r.target === 'number' && r.target > index) return { ...r, target: r.target - 1 }
      return r
    })
    step.dataLinksOut = step.dataLinksOut
      .filter((l) => l.targetStepIndex !== index)
      .map((l) => ({
        ...l,
        targetStepIndex: l.targetStepIndex !== null && l.targetStepIndex > index ? l.targetStepIndex - 1 : l.targetStepIndex,
      }))
    // A marker whose opening block is gone loses its pointer rather than silently closing the
    // block that slid into that index.
    if (step.regionStartStepIndex === index) step.regionStartStepIndex = null
    else if (step.regionStartStepIndex !== null && step.regionStartStepIndex > index) step.regionStartStepIndex--
  })
}

/** Strip a `{{data:token}}` placeholder (and the line break the board inserted before it). */
export function stripDataToken(text: string | null, token: string): string {
  if (!text) return ''
  return text.replace(new RegExp(`\\n?\\{\\{data:${token}\\}\\}`, 'g'), '')
}

// ---------------------------------------------------------------------------------------------
// Graph analysis (mirrors PipelineService.validateGraph so warnings show before a save attempt)
// ---------------------------------------------------------------------------------------------

export interface GraphAnalysis {
  /**
   * Indexes with no incoming transition. Once the start is explicit this is no longer "the start" -
   * it is just the set of blocks nothing leads to, which is how the board offers a sensible start
   * for a pipeline saved before the start existed.
   */
  roots: number[]
  /** Indexes reachable from the start via wired transitions (the start itself included). */
  reachable: Set<number>
  /** Indexes of steps with no incoming and no outgoing wires at all. */
  isolated: Set<number>
}

function wiredAdjacency(steps: BoardStep[]): number[][] {
  return steps.map((step) =>
    step.routes.flatMap((r) => (typeof r.target === 'number' ? [r.target] : [])),
  )
}

/**
 * The adjacency the engine actually walks: drawn transitions plus the implicit `LOOP_END -> LOOP`
 * return nobody draws. Reachability questions use this; `wouldCreateCycle` deliberately does NOT,
 * because inside a loop body every step already reaches every other through that return, and asking
 * it would refuse every new wire in the body.
 */
export function executionAdjacency(steps: BoardStep[]): number[][] {
  const adjacency = wiredAdjacency(steps)
  steps.forEach((step, i) => {
    const start = step.regionStartStepIndex
    if (step.contentType !== 'LOOP_END' || start === null) return
    if (steps[start]?.contentType === 'LOOP' && !adjacency[i].includes(start)) adjacency[i].push(start)
  })
  return adjacency
}

/** The marker closing the region `startIndex` opens, or -1 when the author has not placed one. */
export function regionEndIndex(steps: BoardStep[], startIndex: number): number {
  const endKind: PipelineStepContentType = steps[startIndex]?.contentType === 'LOOP' ? 'LOOP_END' : 'ORCHESTRATOR_END'
  return steps.findIndex((s) => s.contentType === endKind && s.regionStartStepIndex === startIndex)
}

/**
 * Steps an ORCHESTRATOR delegates: reachable from it without going past its «Приёмка». The end
 * marker itself is excluded - the badge belongs on the work, not on the boundary.
 */
export function regionStepsOf(steps: BoardStep[], orchestratorIndex: number): Set<number> {
  const endIndex = regionEndIndex(steps, orchestratorIndex)
  const adjacency = executionAdjacency(steps)
  const region = new Set<number>()
  const stack = [orchestratorIndex]
  while (stack.length) {
    const current = stack.pop()!
    for (const next of adjacency[current] ?? []) {
      if (region.has(next)) continue
      region.add(next)
      if (next !== endIndex) stack.push(next)
    }
  }
  region.delete(orchestratorIndex)
  if (endIndex >= 0) region.delete(endIndex)
  return region
}

/** Every step inside any orchestrated region, which is where a delegation hint may be set. */
export function orchestratedSteps(steps: BoardStep[]): Set<number> {
  const all = new Set<number>()
  steps.forEach((step, i) => {
    if (step.contentType !== 'ORCHESTRATOR') return
    regionStepsOf(steps, i).forEach((index) => all.add(index))
  })
  return all
}

/** The body of a loop: its body route onward, without passing through the loop again. */
export function loopBodyOf(steps: BoardStep[], loopIndex: number): Set<number> {
  const bodyTarget = steps[loopIndex]?.routes.find((r) => r.outcomeKey === null)?.target
  if (typeof bodyTarget !== 'number') return new Set()
  const adjacency = executionAdjacency(steps)
  const body = new Set<number>([bodyTarget])
  const stack = [bodyTarget]
  while (stack.length) {
    const current = stack.pop()!
    for (const next of adjacency[current] ?? []) {
      if (next === loopIndex || body.has(next)) continue
      body.add(next)
      stack.push(next)
    }
  }
  return body
}

export function analyzeGraph(steps: BoardStep[], startStepIndex: number | null): GraphAnalysis {
  const n = steps.length
  const inDegree = new Array<number>(n).fill(0)
  const outDegree = new Array<number>(n).fill(0)
  const adjacency = executionAdjacency(steps)
  steps.forEach((step, i) => {
    step.routes.forEach((r) => {
      if (r.target === null) return
      outDegree[i]++
      if (typeof r.target === 'number' && r.target >= 0 && r.target < n) inDegree[r.target]++
    })
    // A LOOP_END has no drawn route, but it does lead somewhere - without this it reads as a step
    // with no way out and the "dead end" warnings fire on a correctly wired loop.
    if (step.contentType === 'LOOP_END' && step.regionStartStepIndex !== null) outDegree[i]++
  })
  const entryInDegree = [...inDegree]
  steps.forEach((step, i) => step.routes.forEach((route) => {
    if (typeof route.target !== 'number') return
    if (steps[route.target]?.contentType === 'LOOP' && isAncestor(steps, route.target, i)) {
      entryInDegree[route.target]--
    }
  }))
  const roots: number[] = []
  const isolated = new Set<number>()
  for (let i = 0; i < n; i++) {
    if (inDegree[i] === 0 && outDegree[i] === 0) isolated.add(i)
    else if (entryInDegree[i] === 0) roots.push(i)
  }
  const reachable = new Set<number>()
  const entry = startStepIndex !== null && startStepIndex >= 0 && startStepIndex < n
    ? startStepIndex
    : roots.length > 0 ? roots[0] : null
  if (entry !== null) {
    const stack = [entry]
    reachable.add(entry)
    while (stack.length) {
      const current = stack.pop()!
      for (const next of adjacency[current]) {
        if (!reachable.has(next)) {
          reachable.add(next)
          stack.push(next)
        }
      }
    }
  }
  return { roots, reachable, isolated }
}

/** Would wiring `from -> to` close a cycle over the currently wired transitions? */
export function wouldCreateCycle(steps: BoardStep[], from: number, to: number): boolean {
  if (from === to) return true
  const adjacency = wiredAdjacency(steps)
  const stack = [to]
  const seen = new Set<number>([to])
  while (stack.length) {
    const current = stack.pop()!
    if (current === from) return true
    for (const next of adjacency[current]) {
      if (!seen.has(next)) {
        seen.add(next)
        stack.push(next)
      }
    }
  }
  return false
}

/** Is `target` reachable from `source` over wired transitions (the backend's data-link rule)? */
/**
 * Steps reachable from `start` without passing through a JOIN - i.e. the open fan-out. A watcher is
 * banned inside one (branches are worked by sub-agents) but welcome after the JOIN, where the run is
 * back to a single step worked by the leading agent. Mirrors PipelineService.reachableBeforeAnyJoin.
 */
export function reachableBeforeAnyJoin(steps: BoardStep[], start: number): Set<number> {
  const adjacency = executionAdjacency(steps)
  const seen = new Set<number>()
  const stack = [start]
  while (stack.length) {
    const current = stack.pop()!
    for (const next of adjacency[current]) {
      if (steps[next]?.contentType === 'JOIN') continue
      if (!seen.has(next)) {
        seen.add(next)
        stack.push(next)
      }
    }
  }
  return seen
}

export function isAncestor(steps: BoardStep[], source: number, target: number): boolean {
  if (source === target) return false
  const adjacency = executionAdjacency(steps)
  const stack = [source]
  const seen = new Set<number>([source])
  while (stack.length) {
    const current = stack.pop()!
    for (const next of adjacency[current]) {
      if (next === target) return true
      if (!seen.has(next)) {
        seen.add(next)
        stack.push(next)
      }
    }
  }
  return false
}

export interface BoardIssue {
  stepIndex: number | null
  /** `error` blocks saving on the backend; `warning` saves fine but likely isn't what the author meant. */
  severity: 'error' | 'warning'
  text: string
}

export interface BoardState {
  steps: BoardStep[]
  inputLinks: BoardInputLink[]
  parameterNames: string[]
  variables: BoardVariable[]
  startStepIndex: number | null
}

export function collectIssues(board: BoardState): BoardIssue[] {
  const { steps, inputLinks, parameterNames, variables, startStepIndex } = board
  const issues: BoardIssue[] = []

  // Variables live on the input plate, so their problems are reported even on an empty board.
  const seenVariableNames = new Set<string>()
  const wiredIntoVariable = new Set(inputLinks.map((l) => l.targetVariableName).filter((n): n is string => n !== null))
  variables.forEach((variable, index) => {
    const shown = variable.name.trim() || `Переменная ${index + 1}`
    if (!variable.name.trim()) {
      issues.push({ stepIndex: null, severity: 'error', text: `${shown}: задайте имя` })
    } else if (seenVariableNames.has(variable.name.trim())) {
      issues.push({ stepIndex: null, severity: 'error', text: `Переменная «${shown}» объявлена дважды — имена должны быть разными` })
    } else {
      seenVariableNames.add(variable.name.trim())
    }
    if (!variable.value.trim() && !wiredIntoVariable.has(variable.name)) {
      issues.push({ stepIndex: null, severity: 'error', text: `Переменная «${shown}»: нет значения и ничего не подключено на вход` })
    }
  })

  if (steps.length === 0) return issues

  const knownParameters = new Set(parameterNames)
  const knownVariables = new Set(variables.map((v) => v.name))
  inputLinks.forEach((link) => {
    const known = link.source.kind === 'param' ? knownParameters : knownVariables
    const what = link.source.kind === 'param' ? 'Параметр' : 'Переменная'
    if (!known.has(link.source.name)) {
      issues.push({ stepIndex: link.targetStepIndex, severity: 'error', text: `${what} «${link.source.name}» больше не существует — отсоедините его провод` })
    }
    if (link.targetVariableName !== null && !knownVariables.has(link.targetVariableName)) {
      issues.push({ stepIndex: null, severity: 'error', text: `Провод ведёт в переменную «${link.targetVariableName}», которой больше нет — отсоедините его` })
    }
  })

  const { reachable } = analyzeGraph(steps, startStepIndex)
  if (startStepIndex === null) {
    issues.push({ stepIndex: null, severity: 'error', text: 'Старт не подключён: протяните провод от порта «Старт» на плашке входа к первому блоку' })
  }
  const incomingData = new Array<number>(steps.length).fill(0)
  // Step outputs counted apart from the input plate: a COMMAND watcher may take a value the user
  // typed at run start, but never one a model produced on an earlier step.
  const incomingStepData = new Array<number>(steps.length).fill(0)
  steps.forEach((step) => step.dataLinksOut.forEach((l) => {
    if (l.targetStepIndex !== null) {
      incomingData[l.targetStepIndex]++
      incomingStepData[l.targetStepIndex]++
    }
  }))
  inputLinks.forEach((l) => {
    if (l.targetStepIndex !== null && l.targetStepIndex >= 0 && l.targetStepIndex < steps.length) incomingData[l.targetStepIndex]++
  })
  const orchestrated = orchestratedSteps(steps)
  steps.forEach((step, i) => {
    const title = stepDisplayTitle(step, i)
    if (!reachable.has(i) && startStepIndex !== null && steps.length > 1) {
      issues.push({ stepIndex: i, severity: 'warning', text: `«${title}» недостижим от старта и никогда не выполнится` })
    }
    if (step.contentType === 'CONDITION') {
      if (step.conditionMode === 'PROMPT') {
        if (!step.promptText || !step.promptText.trim()) {
          issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: задайте вопрос, на который модель ответит «да» или «нет»` })
        }
      } else {
        if (incomingData[i] !== 1) {
          issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: условию нужен ровно один вход данных (сейчас ${incomingData[i]})` })
        }
        if (!step.conditionValue || !step.conditionValue.trim()) {
          issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: не задано значение для сравнения` })
        }
      }
      const unwired = step.routes.filter((r) => r.target === null).map((r) => r.outcomeKey)
      if (unwired.length > 0) {
        issues.push({ stepIndex: i, severity: 'warning', text: `«${title}»: ветка ${unwired.join(' и ')} не подключена — путь завершится` })
      }
    }
    if (step.contentType === 'WATCHER') {
      const gitlab = step.watchSource === 'GITLAB_MR'
      if (!step.promptText || !step.promptText.trim()) {
        issues.push({
          stepIndex: i,
          severity: 'error',
          text: gitlab
            ? `«${title}»: укажите ссылку на merge request, за которым следить`
            : `«${title}»: укажите команду-пробу, по которой проверять событие`,
        })
      } else if (gitlab && !TEMPLATE_TOKEN.test(step.promptText) && !MERGE_REQUEST_URL.test(step.promptText)) {
        // A templated target (the MR URL wired in from the step that opened it) can only be checked
        // at run time, so only a literal one is held to the URL shape.
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: ссылка должна вести на merge request (содержать /merge_requests/<номер>) или собираться из подставляемого значения` })
      }
      const interval = step.checkIntervalSeconds
      const timeout = step.watchTimeoutSeconds
      if (interval === null || interval < 10 || interval > 3600) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: интервал проверки — от 10 секунд до 1 часа` })
      }
      if (timeout === null || timeout < 60 || timeout > 86400) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: предельное ожидание — от 1 минуты до 24 часов` })
      } else if (interval !== null && timeout < interval) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: предельное ожидание не может быть меньше интервала проверки` })
      }
      if (!gitlab && incomingStepData[i] > 0) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: в команду нельзя подавать выход предыдущего шага — отсоедините этот провод (значение параметра подавать можно)` })
      }
      const fanOut = steps.findIndex((other, j) => other.contentType === 'PARALLEL' && reachableBeforeAnyJoin(steps, j).has(i))
      if (fanOut >= 0) {
        issues.push({
          stepIndex: i,
          severity: 'error',
          text: `«${title}»: наблюдатель не может стоять внутри развилки «${stepDisplayTitle(steps[fanOut], fanOut)}» — ожидание держит ведущий агент, а ветки выполняют суб-агенты. Перенесите его за блок «Ожидать все»`,
        })
      }
      const unwired = step.routes.filter((r) => r.target === null)
        .map((r) => watcherPortLabel(step.watchSource, step.watchAspect, r.outcomeKey))
      if (unwired.length > 0) {
        issues.push({ stepIndex: i, severity: 'warning', text: `«${title}»: ветка «${unwired.join('», «')}» не подключена — путь завершится` })
      }
    }
    if (step.contentType === 'LOOP') {
      if (step.maxIterations === null || step.maxIterations < 1 || step.maxIterations > 100) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: задайте лимит итераций от 1 до 100` })
      }
      if (step.routes.find((r) => r.outcomeKey === null)?.target === null) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: подключите первый шаг к переходу «тело цикла»` })
      }
      if (step.routes.find((r) => r.outcomeKey === 'done')?.target === null) {
        issues.push({ stepIndex: i, severity: 'warning', text: `«${title}»: выход при лимите не подключён — этот путь завершится` })
      }
      const ends = steps.filter((s) => s.contentType === 'LOOP_END' && s.regionStartStepIndex === i).length
      const backEdge = steps.some((s) => s.routes.some((r) => r.target === i))
      if (ends === 0 && !backEdge) {
        issues.push({
          stepIndex: i,
          severity: 'error',
          text: `«${title}»: тело цикла не замкнуто — поставьте блок «Конец цикла» в конце тела или верните последний шаг в этот блок`,
        })
      }
      if (ends > 1) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: на один цикл только один «Конец цикла»` })
      }
    }
    if (step.contentType === 'LOOP_END') {
      const start = step.regionStartStepIndex
      if (start === null || steps[start]?.contentType !== 'LOOP') {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: выберите цикл, который этот блок замыкает` })
      } else if (!loopBodyOf(steps, start).has(i)) {
        issues.push({
          stepIndex: i,
          severity: 'error',
          text: `«${title}»: блок не стоит в теле цикла «${stepDisplayTitle(steps[start], start)}» — подключите его после последнего шага тела`,
        })
      }
    }
    if (step.contentType === 'PROMPT_LOOP') {
      if (!step.promptText || !step.promptText.trim()) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: напишите промпт, который блок будет повторять` })
      }
      if (step.maxIterations === null || step.maxIterations < 1 || step.maxIterations > 100) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: задайте число итераций от 1 до 100` })
      }
    }
    if (step.contentType === 'ORCHESTRATOR') {
      if (step.maxIterations === null || step.maxIterations < 1 || step.maxIterations > 5) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: задайте число попыток на шаг от 1 до 5` })
      }
      const endIndex = regionEndIndex(steps, i)
      if (endIndex < 0) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: поставьте блок «Приёмка» в конце региона — без него не видно, что отдаётся дешёвым моделям` })
      } else {
        const region = regionStepsOf(steps, i)
        if (region.size === 0) {
          issues.push({ stepIndex: i, severity: 'warning', text: `«${title}»: между оркестратором и приёмкой нет ни одного шага — раздавать нечего` })
        }
        for (const inside of region) {
          if (steps[inside].contentType === 'ORCHESTRATOR') {
            issues.push({ stepIndex: inside, severity: 'error', text: `«${stepDisplayTitle(steps[inside], inside)}»: оркестратор внутри оркестратора — директор на прогон один` })
          }
        }
      }
      const fanOut = steps.findIndex((other, j) => other.contentType === 'PARALLEL' && reachableBeforeAnyJoin(steps, j).has(i))
      if (fanOut >= 0) {
        issues.push({
          stepIndex: i,
          severity: 'error',
          text: `«${title}»: оркестратор не может стоять внутри развилки «${stepDisplayTitle(steps[fanOut], fanOut)}» — регион ведёт одна модель, а ветки выполняют суб-агенты. Перенесите его за блок «Ожидать все»`,
        })
      }
    }
    if (step.contentType === 'ORCHESTRATOR_END' && (step.regionStartStepIndex === null
        || steps[step.regionStartStepIndex]?.contentType !== 'ORCHESTRATOR')) {
      issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: выберите оркестратор, регион которого этот блок закрывает` })
    }
    if (step.delegationHint !== null && !orchestrated.has(i)) {
      issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: подсказка «кому» работает только внутри региона оркестрации` })
    }
    if (step.contentType === 'PARALLEL' && !step.routes.some((r) => typeof r.target === 'number')) {
      issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: ни одна ветка не подключена к блоку` })
    }
    if (step.contentType === 'JOIN' && !steps.some((s) => s.routes.some((r) => r.target === i))) {
      issues.push({ stepIndex: i, severity: 'warning', text: `«${title}»: в блок не входит ни одна ветка — ждать нечего` })
    }
    if (step.contentType === 'MD_FILE' && step.assetId === null) {
      issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: не загружен .md-файл` })
    }
    if (step.contentType === 'PROMPT' && (!step.promptText || !step.promptText.trim())) {
      issues.push({ stepIndex: i, severity: 'warning', text: `«${title}»: пустая инструкция` })
    }
    if (step.outputs.some((o) => !o.name.trim())) {
      issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: выход без имени` })
    }
    const names = step.outputs.map((o) => o.name.trim()).filter(Boolean)
    if (new Set(names).size !== names.length) {
      issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: имена выходов повторяются` })
    }
    const keys = step.routes.map((r) => r.outcomeKey).filter((k): k is string => k !== null && k.trim() !== '')
    if (new Set(keys).size !== keys.length) {
      issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: ветки с одинаковым ключом` })
    }
    if (step.routes.some((r) => r.outcomeKey !== null && r.outcomeKey.trim() === '')) {
      issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: ветка без ключа — задайте ключ или удалите её` })
    }
    step.dataLinksOut.forEach((link) => {
      if (link.targetStepIndex !== null && !isAncestor(steps, i, link.targetStepIndex)) {
        const target = steps[link.targetStepIndex]
        issues.push({
          stepIndex: i,
          severity: 'error',
          text: `«${title}» передаёт данные в «${stepDisplayTitle(target, link.targetStepIndex)}», но переходами туда не попасть — данные идут только по ходу выполнения`,
        })
      }
    })
  })
  return issues
}
