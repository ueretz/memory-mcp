import type {
  PipelineConditionOperator,
  PipelineDetail,
  PipelineStepContentType,
  PipelineUpsertDataLink,
  PipelineUpsertOutput,
  PipelineUpsertStep,
} from '@/api/types'

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
}

export interface BlockKindMeta {
  kind: PipelineStepContentType
  label: string
  description: string
  icon: string
  /** CSS color for the kind's accent (icon tile, header stripe). */
  color: string
}

export const BLOCK_KINDS: BlockKindMeta[] = [
  { kind: 'PROMPT', label: 'Промпт', description: 'Инструкция для Claude', icon: 'sparkles', color: 'var(--color-accent)' },
  { kind: 'MD_FILE', label: 'MD-файл', description: 'Инструкция из загруженного файла', icon: 'document', color: '#2563eb' },
  { kind: 'CONDITION', label: 'Условие', description: 'Сравнивает значение и выбирает ветку', icon: 'branch', color: '#d97706' },
  { kind: 'VARIABLE', label: 'Переменная', description: 'Фиксированное значение как выход', icon: 'variable', color: '#0d9488' },
]

export const BLOCK_KIND_BY_TYPE: Record<PipelineStepContentType, BlockKindMeta> = Object.fromEntries(
  BLOCK_KINDS.map((k) => [k.kind, k]),
) as Record<PipelineStepContentType, BlockKindMeta>

/** Wire/pin colors shared by the board and the read-only views. */
export const WIRE_COLORS = {
  flow: '#8b8fa3',
  flowSelected: 'var(--color-accent)',
  data: '#10b981',
  trueBranch: '#16a34a',
  falseBranch: '#dc2626',
}

export const DEFAULT_ROUTE_LABEL = 'далее'

/** Can this kind of step have named (outcome-keyed) transition ports added by the author? */
export function supportsNamedBranches(kind: PipelineStepContentType): boolean {
  return kind === 'PROMPT' || kind === 'MD_FILE'
}

/** Does this kind of step take a data input wire? */
export function acceptsDataInput(kind: PipelineStepContentType): boolean {
  return kind !== 'VARIABLE'
}

/** Can the author add/remove data outputs on this kind of step? */
export function hasEditableOutputs(kind: PipelineStepContentType): boolean {
  return kind === 'PROMPT' || kind === 'MD_FILE'
}

export function stepDisplayTitle(step: { title: string }, index: number): string {
  return step.title.trim() || `Шаг ${index + 1}`
}

export function newBoardStep(kind: PipelineStepContentType, position: { x: number; y: number }): BoardStep {
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
  }
  if (kind === 'CONDITION') {
    step.conditionOperator = 'EQUALS'
    step.conditionValue = ''
  } else if (kind === 'VARIABLE') {
    step.outputs = [{ name: 'value' }]
  }
  ensureFixedPorts(step)
  return step
}

/**
 * Every card shows the transition ports its kind requires, wired or not:
 * - PROMPT / MD_FILE / VARIABLE: exactly one default port ("далее"), kept last.
 * - CONDITION: exactly `true` and `false`.
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
  const named = step.routes.filter((r) => r.outcomeKey !== null)
  const defaults = step.routes.filter((r) => r.outcomeKey === null)
  const defaultRoute = defaults[0] ?? { outcomeKey: null, target: null }
  step.routes = step.contentType === 'VARIABLE' ? [defaultRoute] : [...named, defaultRoute]
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
    if (step.contentType === 'CONDITION') return
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
        // Unwired ports are a board-only affordance - the API has no representation for them.
        // A CONDITION branch is the exception: the backend insists on both keys being present,
        // so an unwired branch is sent as "end of run" rather than omitted.
        .filter((r) => r.target !== null || step.contentType === 'CONDITION')
        .map((r) => ({
          outcomeKey: r.outcomeKey && r.outcomeKey.trim() !== '' ? r.outcomeKey.trim() : null,
          targetStepIndex: r.target === 'end' || r.target === null ? null : r.target,
        })),
      outputs: step.outputs.map((o) => ({ name: o.name.trim() })),
      dataLinksOut: step.dataLinksOut.filter((link) => declaredOutputs.has(link.sourceOutputName)),
      conditionOperator: step.conditionOperator,
      conditionValue: step.conditionValue,
    }
  })
}

/** Remove step `index` and re-point every route / data link that referenced steps by index. */
export function removeStepAt(steps: BoardStep[], index: number): void {
  steps.splice(index, 1)
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
  /** Indexes of steps the backend would treat as a starting step. */
  roots: number[]
  /** Indexes reachable from the (first) root via wired transitions. */
  reachable: Set<number>
  /** Indexes of steps with no incoming and no outgoing wires at all. */
  isolated: Set<number>
}

function wiredAdjacency(steps: BoardStep[]): number[][] {
  return steps.map((step) =>
    step.routes.flatMap((r) => (typeof r.target === 'number' ? [r.target] : [])),
  )
}

export function analyzeGraph(steps: BoardStep[]): GraphAnalysis {
  const n = steps.length
  const inDegree = new Array<number>(n).fill(0)
  const outDegree = new Array<number>(n).fill(0)
  const adjacency = wiredAdjacency(steps)
  steps.forEach((step, i) => {
    step.routes.forEach((r) => {
      if (r.target === null) return
      outDegree[i]++
      if (typeof r.target === 'number' && r.target >= 0 && r.target < n) inDegree[r.target]++
    })
  })
  const roots: number[] = []
  const isolated = new Set<number>()
  for (let i = 0; i < n; i++) {
    if (inDegree[i] === 0 && outDegree[i] === 0) isolated.add(i)
    else if (inDegree[i] === 0) roots.push(i)
  }
  const reachable = new Set<number>()
  if (roots.length > 0) {
    const stack = [roots[0]]
    reachable.add(roots[0])
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
export function isAncestor(steps: BoardStep[], source: number, target: number): boolean {
  if (source === target) return false
  const adjacency = wiredAdjacency(steps)
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

export function collectIssues(steps: BoardStep[]): BoardIssue[] {
  const issues: BoardIssue[] = []
  if (steps.length === 0) return issues
  const { roots, reachable, isolated } = analyzeGraph(steps)
  if (roots.length === 0 && steps.length > 0 && isolated.size < steps.length) {
    issues.push({ stepIndex: null, severity: 'error', text: 'Нет стартового блока: у каждого блока есть входящий переход' })
  }
  if (roots.length > 1) {
    issues.push({
      stepIndex: null,
      severity: 'error',
      text: `Несколько стартовых блоков: ${roots.map((i) => stepDisplayTitle(steps[i], i)).join(', ')}. Оставьте один без входящих переходов`,
    })
  }
  const incomingData = new Array<number>(steps.length).fill(0)
  steps.forEach((step) => step.dataLinksOut.forEach((l) => {
    if (l.targetStepIndex !== null) incomingData[l.targetStepIndex]++
  }))
  steps.forEach((step, i) => {
    const title = stepDisplayTitle(step, i)
    if (!reachable.has(i) && roots.length > 0 && steps.length > 1) {
      issues.push({ stepIndex: i, severity: 'warning', text: `«${title}» недостижим от старта и никогда не выполнится` })
    }
    if (step.contentType === 'CONDITION') {
      if (incomingData[i] !== 1) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: условию нужен ровно один вход данных (сейчас ${incomingData[i]})` })
      }
      if (!step.conditionValue || !step.conditionValue.trim()) {
        issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: не задано значение для сравнения` })
      }
      const unwired = step.routes.filter((r) => r.target === null).map((r) => r.outcomeKey)
      if (unwired.length > 0) {
        issues.push({ stepIndex: i, severity: 'warning', text: `«${title}»: ветка ${unwired.join(' и ')} не подключена — путь завершится` })
      }
    }
    if (step.contentType === 'VARIABLE' && (!step.promptText || !step.promptText.trim())) {
      issues.push({ stepIndex: i, severity: 'error', text: `«${title}»: у переменной нет значения` })
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
