<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import { NodeResizer, type OnResize } from '@vue-flow/node-resizer'
import { computed } from 'vue'

import type { PipelineConditionOperator } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import {
  BLOCK_KIND_BY_TYPE,
  DEFAULT_ROUTE_LABEL,
  acceptsDataInput,
  hasEditableOutputs,
  hasOutputs,
  supportsAddingPorts,
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

const OPERATORS: { value: PipelineConditionOperator; label: string }[] = [
  { value: 'EQUALS', label: 'равно' },
  { value: 'GREATER_THAN', label: 'больше' },
  { value: 'LESS_THAN', label: 'меньше' },
  { value: 'GREATER_OR_EQUAL', label: 'не меньше' },
  { value: 'LESS_OR_EQUAL', label: 'не больше' },
]

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
      <span v-if="data.isStart" class="pl-chip pl-chip-start" title="Выполнение начинается с этого блока"><AppIcon name="play" class="size-3" />старт</span>
      <span v-else-if="data.unreachable" class="pl-chip pl-chip-warn" title="До блока нет пути от старта — он никогда не выполнится"><AppIcon name="warning" class="size-3" />недостижим</span>
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
        <div class="pl-condition">
          <span class="pl-condition-lhs">значение</span>
          <select v-model="data.step.conditionOperator" class="pl-select">
            <option v-for="op in OPERATORS" :key="op.value" :value="op.value">{{ op.label }}</option>
          </select>
          <input v-model="data.step.conditionValue" placeholder="10" class="pl-input" />
        </div>
        <p class="pl-hint">Сравнивается значение со входа данных. Ветка выбирается сервером, без Claude.</p>
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
          <span class="pl-port-label">{{ isCondition ? 'Что сравнивать' : 'Входные данные' }}</span>
          <ul v-if="data.wiredInputs.length" class="pl-wired-list">
            <li v-for="input in data.wiredInputs" :key="input.token" class="pl-wired-item">
              <span class="pl-wired-name">{{ input.sourceStepTitle }} <b>·</b> {{ input.sourceOutputName }}</span>
              <button type="button" class="pl-icon-btn pl-icon-btn-xs nodrag" title="Отвязать" @click="data.on.unwireInput(input.token)">
                <AppIcon name="close" class="size-3" />
              </button>
            </li>
          </ul>
          <span v-else class="pl-port-empty">{{ isCondition ? 'подключите выход другого блока' : 'необязательно — выход другого блока' }}</span>
        </div>
      </div>

      <div class="pl-port-group">
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
