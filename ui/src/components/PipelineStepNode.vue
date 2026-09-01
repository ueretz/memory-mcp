<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import { NodeResizer, type OnResize } from '@vue-flow/node-resizer'

import type { PipelineConditionOperator, PipelineUpsertStep } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'

// Declared as the full `NodeProps<Data>` (not just `{ data: ... }`) because vue-flow's
// `:node-types` prop requires each component to be assignable to `NodeComponent`, which expects
// the whole NodeProps shape (id, type, selected, connectable, ...) - a component typed with only
// a `data` prop type-checks fine on its own but fails to satisfy `NodeComponent` when registered.
// The custom fields all live under the single `data` prop this generic produces (mirroring the
// original label/outputs/contentType version of this component) - there is no flattening.
//
// `data.step` is the SAME reactive object the board keeps in its `steps` array, not a copy - every
// `v-model` in this template mutates it directly, so there is no separate emit/update plumbing for
// simple field edits. Structural changes (delete, add/remove an output, upload a file, resize)
// still need the board's own methods, so those come through as plain callback props instead.
defineProps<
  NodeProps<{
    step: PipelineUpsertStep | null
    label: string
    isEnd: boolean
    readonly?: boolean
    wiredInputs?: { token: string; sourceStepTitle: string; sourceOutputName: string }[]
    onRemove?: () => void
    onAddOutput?: () => void
    onRemoveOutput?: (outputIndex: number) => void
    onMdFileChosen?: (event: Event) => void
    onReferenceFileChosen?: (event: Event) => void
    onResize?: (size: { width: number; height: number }) => void
  }>
>()

const OPERATORS: { value: PipelineConditionOperator; label: string }[] = [
  { value: 'EQUALS', label: '=' },
  { value: 'GREATER_THAN', label: '>' },
  { value: 'LESS_THAN', label: '<' },
  { value: 'GREATER_OR_EQUAL', label: '>=' },
  { value: 'LESS_OR_EQUAL', label: '<=' },
]
</script>

<template>
  <div v-if="data.isEnd" class="pipeline-card pipeline-card-end">
    <Handle id="data-in" type="target" :position="Position.Left" class="pipeline-handle pipeline-handle-route" />
    <span class="pipeline-card-end-label">{{ data.label }}</span>
  </div>

  <div
    v-else-if="data.step"
    class="pipeline-card h-full w-full"
    :class="[`pipeline-card-${data.step.contentType.toLowerCase()}`, { 'pipeline-card-readonly': data.readonly }]"
  >
    <NodeResizer
      v-if="!data.readonly"
      :min-width="240"
      :min-height="150"
      line-class-name="pipeline-resize-line"
      handle-class-name="pipeline-resize-handle"
      @resize="(event: OnResize) => data.onResize?.({ width: event.params.width, height: event.params.height })"
    />

    <Handle id="data-in" type="target" :position="Position.Left" class="pipeline-handle pipeline-handle-route" />

    <div class="pipeline-card-header">
      <input
        v-model="data.step.title"
        :disabled="data.readonly"
        placeholder="Название шага"
        class="pipeline-card-title nodrag"
      />
      <button v-if="!data.readonly" type="button" class="pipeline-card-remove nodrag" title="Удалить шаг" @click="data.onRemove?.()">
        <AppIcon name="trash" class="size-3.5" />
      </button>
    </div>

    <div class="pipeline-card-body nodrag nowheel">
      <template v-if="data.step.contentType === 'PROMPT'">
        <textarea
          v-model="data.step.promptText"
          :disabled="data.readonly"
          placeholder="Инструкция для Claude — можно {{paramName}}"
          class="pipeline-card-textarea"
        />
      </template>

      <template v-else-if="data.step.contentType === 'MD_FILE'">
        <input v-if="!data.readonly" type="file" accept=".md" class="pipeline-card-file" @change="data.onMdFileChosen?.($event)" />
        <span v-if="data.step.assetId" class="pipeline-card-hint">Загружен: asset #{{ data.step.assetId }}</span>
      </template>

      <template v-else-if="data.step.contentType === 'CONDITION'">
        <div class="pipeline-card-row">
          <select v-model="data.step.conditionOperator" :disabled="data.readonly" class="pipeline-card-select">
            <option v-for="op in OPERATORS" :key="op.value" :value="op.value">{{ op.label }}</option>
          </select>
          <input v-model="data.step.conditionValue" :disabled="data.readonly" placeholder="напр. 10" class="pipeline-card-input" />
        </div>
        <p class="pipeline-card-hint">Сравнивается со входящей связью. Ветка выбирается автоматически.</p>
      </template>

      <template v-else-if="data.step.contentType === 'VARIABLE'">
        <input v-model="data.step.promptText" :disabled="data.readonly" placeholder="напр. hello" class="pipeline-card-input" />
        <p class="pipeline-card-hint">Публикуется в единственный output автоматически, без Claude.</p>
      </template>

      <div v-if="data.step.contentType === 'PROMPT' || data.step.contentType === 'MD_FILE'" class="pipeline-card-refs">
        <label class="pipeline-card-label">Ссылочный файл:</label>
        <input v-if="!data.readonly" type="file" class="pipeline-card-file" @change="data.onReferenceFileChosen?.($event)" />
        <span v-if="data.step.referenceAssetId" class="pipeline-card-hint">#{{ data.step.referenceAssetId }}</span>
      </div>

      <div v-if="data.wiredInputs && data.wiredInputs.length" class="pipeline-card-wired">
        <p v-for="input in data.wiredInputs" :key="input.token" class="pipeline-card-hint">
          ← {{ input.sourceStepTitle }}.{{ input.sourceOutputName }}
        </p>
      </div>

      <div v-if="data.step.contentType !== 'CONDITION'" class="pipeline-card-outputs">
        <div class="pipeline-card-outputs-header">
          <span class="pipeline-card-label">Выходы</span>
          <button
            v-if="!data.readonly && !(data.step.contentType === 'VARIABLE' && data.step.outputs.length >= 1)"
            type="button" class="pipeline-card-add nodrag" @click="data.onAddOutput?.()"
          >+</button>
        </div>
        <div v-for="(output, outputIndex) in data.step.outputs" :key="outputIndex" class="pipeline-card-output-row">
          <input v-model="output.name" :disabled="data.readonly" placeholder="имя" class="pipeline-card-output-input nodrag" />
          <button
            v-if="!data.readonly && data.step.contentType !== 'VARIABLE'"
            type="button" class="pipeline-card-remove-small nodrag" @click="data.onRemoveOutput?.(outputIndex)"
          >×</button>
          <Handle
            :id="`output-${output.name}`"
            type="source"
            :position="Position.Right"
            class="pipeline-handle pipeline-handle-data pipeline-handle-inline"
          />
        </div>
      </div>
    </div>

    <template v-if="data.step.contentType === 'CONDITION'">
      <Handle id="route-true" type="source" :position="Position.Right" title="true" class="pipeline-handle pipeline-handle-true" />
      <Handle id="route-false" type="source" :position="Position.Bottom" title="false" class="pipeline-handle pipeline-handle-false" />
    </template>
    <Handle v-else id="route" type="source" :position="Position.Right" class="pipeline-handle pipeline-handle-route" />
  </div>
</template>
