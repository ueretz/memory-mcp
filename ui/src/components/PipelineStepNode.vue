<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import { NodeResizer, type OnResize } from '@vue-flow/node-resizer'
import { computed, ref, watch } from 'vue'

import type { PipelineConditionOperator, PipelineStepContentType } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import {
  BLOCK_KIND_BY_TYPE,
  DEFAULT_ROUTE_LABEL,
  DELEGATION_HINTS,
  REGION_START_KIND,
  acceptsDataInput,
  ensureFixedPorts,
  hasEditableOutputs,
  hasOutputs,
  supportsAddingPorts,
  watcherPortLabel,
  type BoardStep,
} from '@/lib/pipelineBoard'

/**
 * Editable step card for the pipeline board.
 *
 * Every wire attaches to a named port, and every port the step can have is drawn on the card up
 * front - nothing appears only after a drag:
 *   - `flow-in`        (left, header)   incoming transition
 *   - `data-in`        (left, footer)   incoming data wire (not on VARIABLE)
 *   - `flow-out-<i>`   (right, footer)  one per transition port: named branches, then "далее";
 *                                       CONDITION has exactly `true` and `false`
 *   - `data-out-<i>`   (right, footer)  one per declared output
 * Port handles are keyed by INDEX, not name: vue-flow caches handle ids at mount, so a name-based
 * id would report the pre-rename name on the next drag.
 *
 * `data.step` is the same reactive object the board keeps in its `steps` array - `v-model`s here
 * mutate it directly. Structural changes go through the `data.on` callbacks.
 */
export interface StepNodeActions {
  remove: () => void
  addOutput: () => void
  removeOutput: (outputIndex: number) => void
  renameOutput: (outputIndex: number, name: string) => void
  addBranch: () => void
  removeBranch: (routeIndex: number) => void
  unwireRoute: (routeIndex: number) => void
  unwireInput: (token: string) => void
  mdFileChosen: (event: Event) => void
  referenceFileChosen: (event: Event) => void
  resize: (size: { width: number; height: number }) => void
}

/** A candidate for the "which block does this close?" picker on a region marker. */
export interface RegionStartOption {
  index: number
  label: string
}

export interface WiredInput {
  token: string
  sourceStepTitle: string
  sourceOutputName: string
}

const props = defineProps<
  NodeProps<{
    step: BoardStep
    index: number
    isStart: boolean
    unreachable: boolean
    /** Blocks this marker could close - only on LOOP_END / ORCHESTRATOR_END cards. */
    regionStartOptions: RegionStartOption[]
    /** Whether this step sits inside an orchestrated region, i.e. gets delegated. */
    orchestrated: boolean
    flowInWired: boolean
    wiredInputs: WiredInput[]
    routeTargets: (string | null)[]
    on: StepNodeActions
  }>
>()

const kind = computed(() => BLOCK_KIND_BY_TYPE[props.data.step.contentType])
const canAddBranch = computed(() => supportsAddingPorts(props.data.step.contentType))
const isParallel = computed(() => props.data.step.contentType === 'PARALLEL')
const showOutputs = computed(() => hasOutputs(props.data.step.contentType))
const showDataIn = computed(() => acceptsDataInput(props.data.step.contentType))
const editableOutputs = computed(() => hasEditableOutputs(props.data.step.contentType))
const isCondition = computed(() => props.data.step.contentType === 'CONDITION')
const isLoop = computed(() => props.data.step.contentType === 'LOOP')
const isWatcher = computed(() => props.data.step.contentType === 'WATCHER')
const regionStartKind = computed<PipelineStepContentType | undefined>(() => REGION_START_KIND[props.data.step.contentType])
const regionStartLabel = computed(() => (regionStartKind.value === 'LOOP' ? 'Цикл' : 'Оркестратор'))
/** The "?" popover on the orchestrator card: open on click, not on hover - it is a paragraph. */
const helpOpen = ref(false)
const showDelegationHint = computed(() =>
  props.data.orchestrated
  && (props.data.step.contentType === 'PROMPT'
    || props.data.step.contentType === 'MD_FILE'
    || props.data.step.contentType === 'PROMPT_LOOP'),
)

/** Minutes are what an author thinks in; the model and the API stay in seconds. */
function minutesField(secondsKey: 'checkIntervalSeconds' | 'watchTimeoutSeconds') {
  return computed({
    get: () => {
      const seconds = props.data.step[secondsKey]
      return seconds === null ? null : Math.round((seconds / 60) * 100) / 100
    },
    set: (minutes: number | null) => {
      props.data.step[secondsKey] = minutes === null || Number.isNaN(minutes)
        ? null
        : Math.round(minutes * 60)
    },
  })
}

const checkIntervalMinutes = minutesField('checkIntervalSeconds')
const watchTimeoutMinutes = minutesField('watchTimeoutSeconds')

/**
 * Changing what a watcher watches changes which outcome ports it has, so the port set (and any
 * wire on a port that no longer exists) is rebuilt right here rather than on the next save.
 */
function watchTargetChanged(sourceChanged = false) {
  if (props.data.step.watchSource === 'COMMAND') {
    props.data.step.watchAspect = null
  } else if (props.data.step.watchAspect === null) {
    props.data.step.watchAspect = 'PIPELINE'
  }
  // An MR URL left behind in a probe-command field saves cleanly and then fails every single probe
  // until the timeout fires hours later, so the target does not survive a change of source.
  if (sourceChanged) {
    props.data.step.promptText = ''
  }
  ensureFixedPorts(props.data.step)
}

const OPERATORS: { value: PipelineConditionOperator; label: string }[] = [
  { value: 'EQUALS', label: 'равно' },
  { value: 'GREATER_THAN', label: 'больше' },
  { value: 'LESS_THAN', label: 'меньше' },
  { value: 'GREATER_OR_EQUAL', label: 'не меньше' },
  { value: 'LESS_OR_EQUAL', label: 'не больше' },
]

/**
 * Built in script rather than in the template: the GitLab half names the `{{data:...}}` token, and
 * a literal `{{` inside a template interpolation ends it early.
 */
const watcherHint = computed(() => {
  const source = props.data.step.watchSource === 'COMMAND'
    ? 'Событие наступило, когда команда завершилась с кодом 0; любой другой код — ждём дальше. Подставлять данные с предыдущих шагов в команду нельзя.'
    : 'Ссылку на MR можно подать выходом предыдущего шага через токен {{data:...}} — например того шага, который этот MR и создал.'
  return `${source} Проверки идут фоновым поллером и почти не тратят токенов, но ожидание держится на открытой сессии агента: закроете терминал — прогон встанет и продолжится с того же места, когда его возобновят.`
})

// A hint only means something to a director, so leaving a region clears it instead of saving a
// value the backend would then reject.
watch(() => props.data.orchestrated, (inRegion) => {
  if (!inRegion) props.data.step.delegationHint = null
})

function pinClassForRoute(outcomeKey: string | null): string {
  if (outcomeKey === 'true' && isCondition.value) return 'pl-pin-true'
  if (outcomeKey === 'false' && isCondition.value) return 'pl-pin-false'
  return ''
}
</script>

<template>
  <div class="pl-card" :class="[`pl-card-${data.step.contentType.toLowerCase()}`, { 'pl-card-unreachable': data.unreachable }]" :style="{ '--kind': kind.color }">
    <NodeResizer
      :min-width="280"
      :min-height="160"
      line-class-name="pl-resize-line"
      handle-class-name="pl-resize-handle"
      @resize="(event: OnResize) => data.on.resize({ width: event.params.width, height: event.params.height })"
    />

    <header class="pl-card-head">
      <Handle
        id="flow-in"
        type="target"
        :position="Position.Left"
        class="pl-pin pl-pin-flow pl-pin-flow-in"
        :class="{ 'pl-pin-wired': data.flowInWired }"
        title="Вход перехода"
      />
      <span class="pl-kind-tile"><AppIcon :name="kind.icon" class="size-3.5" /></span>
      <div class="pl-head-text">
        <span class="pl-kind-label">{{ kind.label }} · {{ data.index + 1 }}</span>
        <input v-model="data.step.title" placeholder="Название шага" class="pl-title nodrag" />
      </div>
      <span v-if="data.orchestrated" class="pl-chip pl-chip-orchestrated" title="Шаг внутри региона оркестрации: его раздаёт директор"><AppIcon name="fanout" class="size-3" />делегируется</span>
      <span v-if="data.isStart" class="pl-chip pl-chip-start" title="Выполнение начинается с этого блока"><AppIcon name="play" class="size-3" />старт</span>
      <span v-else-if="data.unreachable" class="pl-chip pl-chip-warn" title="До блока нет пути от старта — он никогда не выполнится"><AppIcon name="warning" class="size-3" />недостижим</span>
      <button
        v-if="data.step.contentType === 'ORCHESTRATOR'"
        type="button"
        class="pl-icon-btn nodrag"
        title="Что делает этот блок"
        @click="helpOpen = !helpOpen"
      >
        <AppIcon name="question" class="size-3.5" />
      </button>
      <button type="button" class="pl-icon-btn nodrag" title="Удалить блок" @click="data.on.remove()">
        <AppIcon name="trash" class="size-3.5" />
      </button>
    </header>

    <section class="pl-card-body nodrag nowheel">
      <template v-if="data.step.contentType === 'PROMPT'">
        <textarea
          v-model="data.step.promptText"
          rows="4"
          placeholder="Что должен сделать Claude на этом шаге. Параметры пайплайна доступны как {{имя}}."
          class="pl-textarea"
        />
      </template>

      <template v-else-if="data.step.contentType === 'MD_FILE'">
        <label class="pl-file">
          <input type="file" accept=".md" class="sr-only" @change="data.on.mdFileChosen($event)" />
          <AppIcon name="document" class="size-3.5" />
          <span>{{ data.step.assetId ? `Файл #${data.step.assetId} загружен — заменить` : 'Загрузить .md с инструкцией' }}</span>
        </label>
      </template>

      <template v-else-if="data.step.contentType === 'CONDITION'">
        <select v-model="data.step.conditionMode" class="pl-select mb-2">
          <option value="VALUE">Сравнение значения</option>
          <option value="PROMPT">Вопрос модели — да / нет</option>
        </select>
        <template v-if="data.step.conditionMode === 'PROMPT'">
          <textarea
            v-model="data.step.promptText"
            rows="3"
            placeholder="Например: исправлены все замечания ревью?"
            class="pl-textarea"
          />
          <p class="pl-hint">Модель отвечает на вопрос и выбирает ветку true («да») или false («нет»).</p>
        </template>
        <template v-else>
          <div class="pl-condition">
            <span class="pl-condition-lhs">значение</span>
            <select v-model="data.step.conditionOperator" class="pl-select">
              <option v-for="op in OPERATORS" :key="op.value" :value="op.value">{{ op.label }}</option>
            </select>
            <input v-model="data.step.conditionValue" placeholder="10" class="pl-input" />
          </div>
          <p class="pl-hint">Сравнивается значение со входа данных. Ветка выбирается сервером, без модели.</p>
        </template>
      </template>

      <template v-else-if="data.step.contentType === 'VARIABLE'">
        <input v-model="data.step.promptText" placeholder="Значение, например: src/config" class="pl-input" />
        <p class="pl-hint">Публикуется как выход «{{ data.step.outputs[0]?.name || 'value' }}» сразу при запуске, без Claude.</p>
      </template>

      <template v-else-if="data.step.contentType === 'PARALLEL'">
        <p class="pl-hint">Все ветки стартуют одновременно: каждую выполняет отдельный суб-агент. Чтобы дождаться их и продолжить, соедините ветки с блоком «Ожидать все».</p>
      </template>

      <template v-else-if="data.step.contentType === 'JOIN'">
        <p class="pl-hint">Ждёт, пока завершатся все входящие ветки, и только потом переходит дальше.</p>
      </template>

      <template v-else-if="data.step.contentType === 'LOOP'">
        <label class="mb-1 block text-[12px] text-muted">Максимум итераций</label>
        <input v-model.number="data.step.maxIterations" type="number" min="1" max="100" class="pl-input" />
        <p class="pl-hint">Соедините «тело цикла» с первым шагом, а последний шаг верните в этот блок. Для досрочного выхода направьте ветку результата мимо цикла.</p>
      </template>

      <template v-else-if="data.step.contentType === 'LOOP_END'">
        <label class="mb-1 block text-[12px] text-muted">{{ regionStartLabel }}</label>
        <select v-model="data.step.regionStartStepIndex" class="pl-select">
          <option :value="null">— выберите блок —</option>
          <option v-for="option in data.regionStartOptions" :key="option.index" :value="option.index">{{ option.label }}</option>
        </select>
        <p class="pl-hint">Поток сам вернётся в начало цикла — обратный провод рисовать не нужно. Цикл решит, начинать ли следующую итерацию или выходить по лимиту.</p>
      </template>

      <template v-else-if="data.step.contentType === 'PROMPT_LOOP'">
        <label class="mb-1 block text-[12px] text-muted">Сколько раз повторить</label>
        <input v-model.number="data.step.maxIterations" type="number" min="1" max="100" class="pl-input mb-2" />
        <textarea
          v-model="data.step.promptText"
          rows="4"
          placeholder="Например: проведи ревью, после поправь ошибки"
          class="pl-textarea"
        />
        <p class="pl-hint">Блок повторяет свой промпт заданное число раз сам в себе — тело рёбрами набрасывать не нужно. Номер итерации считает сервер, он же виден в прогоне.</p>
      </template>

      <template v-else-if="data.step.contentType === 'ORCHESTRATOR'">
        <label class="mb-1 block text-[12px] text-muted">Попыток на шаг</label>
        <input v-model.number="data.step.maxIterations" type="number" min="1" max="5" class="pl-input" />
        <p v-if="helpOpen" class="pl-help">
          Внутри региона работу делают дешёвые модели под присмотром дорогой: ведущая модель не выполняет
          шаги сама, а раздаёт их, запускает и принимает результат, а не прошедшее приёмку повторяет —
          до указанного числа попыток, потом моделью сильнее, потом сама. Для этого нужен скилл
          <b>pipelines</b> и хотя бы один локальный CLI, которым можно запустить модель. Если ничего
          не нашлось, шаги просто выполнит сам агент — прогон не упадёт.
        </p>
        <p v-else class="pl-hint">Регион до блока «Приёмка» раздаётся дешёвым моделям. Подробнее — по знаку «?» в заголовке.</p>
      </template>

      <template v-else-if="data.step.contentType === 'ORCHESTRATOR_END'">
        <label class="mb-1 block text-[12px] text-muted">{{ regionStartLabel }}</label>
        <select v-model="data.step.regionStartStepIndex" class="pl-select">
          <option :value="null">— выберите блок —</option>
          <option v-for="option in data.regionStartOptions" :key="option.index" :value="option.index">{{ option.label }}</option>
        </select>
        <p class="pl-hint">Конец региона: дальше работает сам ведущий агент.</p>
      </template>

      <template v-else-if="data.step.contentType === 'WATCHER'">
        <select v-model="data.step.watchSource" class="pl-select mb-2" @change="watchTargetChanged(true)">
          <option value="GITLAB_MR">Merge request в GitLab</option>
          <option value="COMMAND">Команда-проба</option>
        </select>
        <select
          v-if="data.step.watchSource === 'GITLAB_MR'"
          v-model="data.step.watchAspect"
          class="pl-select mb-2"
          @change="watchTargetChanged()"
        >
          <option value="PIPELINE">Сборка: прошла / упала</option>
          <option value="REVIEW">Ревью: апрув / замечания</option>
        </select>
        <input
          v-model="data.step.promptText"
          :placeholder="data.step.watchSource === 'COMMAND' ? 'test -f /tmp/ready' : 'https://gitlab.example.com/group/project/-/merge_requests/42'"
          class="pl-input mb-2"
        />
        <div class="pl-watch-timings">
          <label class="pl-watch-timing">
            <span class="text-[12px] text-muted">Проверять каждые, мин</span>
            <input v-model.number="checkIntervalMinutes" type="number" min="0.17" step="0.5" class="pl-input" />
          </label>
          <label class="pl-watch-timing">
            <span class="text-[12px] text-muted">Ждать не дольше, мин</span>
            <input v-model.number="watchTimeoutMinutes" type="number" min="1" step="5" class="pl-input" />
          </label>
        </div>
        <p class="pl-hint">{{ watcherHint }}</p>
      </template>

      <label v-if="showDelegationHint" class="mt-2 block">
        <span class="mb-1 block text-[12px] text-muted">Кому отдать</span>
        <select v-model="data.step.delegationHint" class="pl-select">
          <option v-for="hint in DELEGATION_HINTS" :key="String(hint.value)" :value="hint.value">{{ hint.label }}</option>
        </select>
      </label>

      <label v-if="data.step.contentType === 'PROMPT' || data.step.contentType === 'MD_FILE'" class="pl-file pl-file-secondary">
        <input type="file" class="sr-only" @change="data.on.referenceFileChosen($event)" />
        <AppIcon name="link" class="size-3.5" />
        <span>{{ data.step.referenceAssetId ? `Справочный файл #${data.step.referenceAssetId}` : 'Приложить справочный файл' }}</span>
      </label>
    </section>

    <footer class="pl-ports">
      <div v-if="showDataIn" class="pl-port-row pl-port-row-in">
        <Handle
          id="data-in"
          type="target"
          :position="Position.Left"
          class="pl-pin pl-pin-data"
          :class="{ 'pl-pin-wired': data.wiredInputs.length > 0 }"
          title="Вход данных"
        />
        <div class="pl-port-in-body">
          <span class="pl-port-label">{{ isCondition ? (data.step.conditionMode === 'PROMPT' ? 'Контекст вопроса' : 'Что сравнивать') : 'Входные данные' }}</span>
          <ul v-if="data.wiredInputs.length" class="pl-wired-list">
            <li v-for="input in data.wiredInputs" :key="input.token" class="pl-wired-item">
              <span class="pl-wired-name">{{ input.sourceStepTitle }} <b>·</b> {{ input.sourceOutputName }}</span>
              <button type="button" class="pl-icon-btn pl-icon-btn-xs nodrag" title="Отвязать" @click="data.on.unwireInput(input.token)">
                <AppIcon name="close" class="size-3" />
              </button>
            </li>
          </ul>
          <span v-else class="pl-port-empty">{{ isCondition && data.step.conditionMode !== 'PROMPT' ? 'подключите выход другого блока' : 'необязательно — выход другого блока' }}</span>
        </div>
      </div>

      <div v-if="data.step.contentType === 'LOOP_END'" class="pl-port-row pl-port-row-out">
        <span class="pl-branch-default">возврат в цикл</span>
        <span class="pl-port-target">{{ data.step.regionStartStepIndex === null ? 'цикл не выбран' : data.regionStartOptions.find((o) => o.index === data.step.regionStartStepIndex)?.label ?? '—' }}</span>
      </div>

      <div v-else class="pl-port-group">
        <div class="pl-port-group-head">
          <span class="pl-port-label">{{ isCondition || isParallel ? 'Ветки' : 'Переходы' }}</span>
          <button v-if="canAddBranch" type="button" class="pl-text-btn nodrag" @click="data.on.addBranch()">
            <AppIcon name="plus" class="size-3" />ветка
          </button>
        </div>
        <div
          v-for="(route, routeIndex) in data.step.routes"
          :key="routeIndex"
          class="pl-port-row pl-port-row-out"
          :class="{ 'pl-port-row-default': route.outcomeKey === null && !isCondition }"
        >
          <template v-if="isCondition">
            <span class="pl-branch-key" :class="route.outcomeKey === 'true' ? 'pl-branch-true' : 'pl-branch-false'">{{ route.outcomeKey }}</span>
          </template>
          <template v-else-if="isLoop">
            <span class="pl-branch-default">{{ route.outcomeKey === 'done' ? 'выход при лимите' : 'тело цикла' }}</span>
          </template>
          <template v-else-if="isWatcher">
            <span class="pl-branch-default">{{ watcherPortLabel(data.step.watchSource, data.step.watchAspect, route.outcomeKey) }}</span>
          </template>
          <template v-else-if="isParallel">
            <span class="pl-branch-default">ветка {{ routeIndex + 1 }}</span>
            <button v-if="data.step.routes.length > 1" type="button" class="pl-icon-btn pl-icon-btn-xs nodrag" title="Удалить ветку" @click="data.on.removeBranch(routeIndex)">
              <AppIcon name="close" class="size-3" />
            </button>
          </template>
          <template v-else-if="route.outcomeKey === null">
            <span class="pl-branch-default">{{ DEFAULT_ROUTE_LABEL }}</span>
          </template>
          <template v-else>
            <input
              v-model="route.outcomeKey"
              placeholder="ключ outcome"
              class="pl-branch-input nodrag"
              title="Claude вернёт это значение как outcome — по нему выбирается переход"
            />
            <button type="button" class="pl-icon-btn pl-icon-btn-xs nodrag" title="Удалить ветку" @click="data.on.removeBranch(routeIndex)">
              <AppIcon name="close" class="size-3" />
            </button>
          </template>
          <span class="pl-port-target" :class="{ 'pl-port-target-empty': data.routeTargets[routeIndex] === null }">
            {{ data.routeTargets[routeIndex] ?? 'не подключён' }}
          </span>
          <button
            v-if="route.target !== null"
            type="button"
            class="pl-icon-btn pl-icon-btn-xs nodrag"
            title="Отсоединить переход"
            @click="data.on.unwireRoute(routeIndex)"
          >
            <AppIcon name="unlink" class="size-3" />
          </button>
          <Handle
            :id="`flow-out-${routeIndex}`"
            type="source"
            :position="Position.Right"
            class="pl-pin pl-pin-flow"
            :class="[pinClassForRoute(route.outcomeKey), { 'pl-pin-wired': route.target !== null }]"
            :title="route.outcomeKey ?? DEFAULT_ROUTE_LABEL"
          />
        </div>
      </div>

      <div v-if="showOutputs" class="pl-port-group">
        <div class="pl-port-group-head">
          <span class="pl-port-label">Выходы</span>
          <button v-if="editableOutputs" type="button" class="pl-text-btn nodrag" @click="data.on.addOutput()">
            <AppIcon name="plus" class="size-3" />выход
          </button>
        </div>
        <p v-if="data.step.outputs.length === 0" class="pl-port-empty pl-port-empty-block">
          Нет выходов. Добавьте, чтобы передать результат шага дальше по проводу.
        </p>
        <div v-for="(output, outputIndex) in data.step.outputs" :key="outputIndex" class="pl-port-row pl-port-row-out">
          <input
            :value="output.name"
            placeholder="имя выхода"
            class="pl-branch-input nodrag"
            @input="data.on.renameOutput(outputIndex, ($event.target as HTMLInputElement).value)"
          />
          <button
            v-if="editableOutputs"
            type="button"
            class="pl-icon-btn pl-icon-btn-xs nodrag"
            title="Удалить выход"
            @click="data.on.removeOutput(outputIndex)"
          >
            <AppIcon name="close" class="size-3" />
          </button>
          <Handle
            :id="`data-out-${outputIndex}`"
            type="source"
            :position="Position.Right"
            class="pl-pin pl-pin-data"
            :class="{ 'pl-pin-wired': data.step.dataLinksOut.some((l) => l.sourceOutputName === output.name) }"
            :title="output.name || 'выход'"
          />
        </div>
      </div>
    </footer>
  </div>
</template>
