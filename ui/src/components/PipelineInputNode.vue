<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'

import type { PipelineUpsertParameter } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import { PARAM_PIN_COLORS, VARIABLE_PIN_COLOR, type BoardVariable } from '@/lib/pipelineBoard'

/**
 * The board's fixed input plate - everything a run already knows before its first step.
 *
 * Three sections, one port shape each:
 *   - `start-out`      (right)  the transition that says where the run begins. Before V24 the start
 *                               was inferred from "the step nothing routes into", which is what
 *                               produced the «нет стартового блока» / «несколько стартовых блоков»
 *                               errors; now the author simply points at it.
 *   - `param-out-<i>`  (right)  a run parameter's value, typed in when the run is started.
 *   - `var-out-<i>`    (right)  a variable's value, written here by the author.
 *   - `var-in-<i>`     (left)   into a variable's value, so one variable can be built out of a
 *                               parameter or another variable - without any of them being a step.
 *
 * Variables used to be steps, which forced a chain of cards across the board just to declare a few
 * constants. They are rows here instead; parameters are edited on the settings screen as before,
 * because their shape (label, type, required) is part of how a run is started.
 *
 * Handles are keyed by INDEX, like every other port on this board: vue-flow caches handle ids at
 * mount, so a name-based id would report the pre-rename name on the next drag.
 */
defineProps<
  NodeProps<{
    parameters: PipelineUpsertParameter[]
    variables: BoardVariable[]
    wiredParameterNames: string[]
    wiredVariableNames: string[]
    variableNamesWithInput: string[]
    startTargetTitle: string | null
    settingsTo: { name: string; params: Record<string, string> }
    on: {
      addVariable: () => void
      removeVariable: (index: number) => void
      renameVariable: (index: number, name: string) => void
    }
  }>
>()

const TYPE_LABEL: Record<string, string> = { STRING: 'текст', NUMBER: 'число', BOOLEAN: 'да/нет' }
</script>

<template>
  <div class="pl-params">
    <header class="pl-params-head">
      <span class="pl-kind-tile pl-params-tile"><AppIcon name="enter" class="size-3.5" /></span>
      <div class="pl-head-text">
        <span class="pl-kind-label">Вход пайплайна</span>
        <span class="pl-params-title">Старт, параметры, переменные</span>
      </div>
      <RouterLink :to="data.settingsTo" class="pl-icon-btn pl-icon-btn-neutral nodrag" title="Изменить параметры в настройках">
        <AppIcon name="cog" class="size-3.5" />
      </RouterLink>
    </header>

    <!-- Start ------------------------------------------------------------------------------ -->
    <div class="pl-plate-section">
      <div class="pl-port-row pl-start-row">
        <span class="pl-plate-caption">Старт</span>
        <span class="pl-start-target" :class="{ 'pl-start-missing': !data.startTargetTitle }">
          {{ data.startTargetTitle ?? 'не подключён' }}
        </span>
        <Handle
          id="start-out"
          type="source"
          :position="Position.Right"
          class="pl-pin pl-pin-flow"
          :class="{ 'pl-pin-wired': !!data.startTargetTitle }"
          title="Первый блок пайплайна"
        />
      </div>
    </div>

    <!-- Run parameters ---------------------------------------------------------------------- -->
    <div class="pl-plate-section">
      <div class="pl-plate-head">
        <span class="pl-plate-caption">Параметры запуска</span>
        <RouterLink :to="data.settingsTo" class="pl-text-btn nodrag" title="Параметры задаются в настройках">изменить</RouterLink>
      </div>
      <p v-if="data.parameters.length === 0" class="pl-plate-empty">Параметров нет — значения никто не вводит при запуске.</p>
      <div v-else class="pl-params-rows">
        <div v-for="(parameter, index) in data.parameters" :key="index" class="pl-port-row pl-params-row">
          <div class="pl-params-row-text">
            <span class="pl-params-name">{{ parameter.name || 'без имени' }}<span v-if="parameter.required" class="pl-params-required" title="Обязательный">*</span></span>
            <span v-if="parameter.label && parameter.label !== parameter.name" class="pl-params-label">{{ parameter.label }}</span>
          </div>
          <span class="pl-params-type" :style="{ color: PARAM_PIN_COLORS[parameter.type], background: `color-mix(in srgb, ${PARAM_PIN_COLORS[parameter.type]} 14%, transparent)` }">
            {{ TYPE_LABEL[parameter.type] ?? parameter.type }}
          </span>
          <Handle
            :id="`param-out-${index}`"
            type="source"
            :position="Position.Right"
            class="pl-pin pl-pin-param"
            :class="{ 'pl-pin-wired': data.wiredParameterNames.includes(parameter.name) }"
            :style="{ borderColor: PARAM_PIN_COLORS[parameter.type], background: data.wiredParameterNames.includes(parameter.name) ? PARAM_PIN_COLORS[parameter.type] : undefined }"
            :title="`Значение параметра ${parameter.name}`"
          />
        </div>
      </div>
    </div>

    <!-- Variables --------------------------------------------------------------------------- -->
    <div class="pl-plate-section">
      <div class="pl-plate-head">
        <span class="pl-plate-caption">Переменные</span>
        <button type="button" class="pl-text-btn nodrag" @click="data.on.addVariable()">+ переменная</button>
      </div>
      <p v-if="data.variables.length === 0" class="pl-plate-empty">
        Постоянные значения пайплайна: ссылка, токен, имя ветки. Подключаются проводом туда, где нужны.
      </p>
      <div v-else class="pl-vars-rows">
        <div v-for="(variable, index) in data.variables" :key="index" class="pl-port-row pl-var-row">
          <Handle
            :id="`var-in-${index}`"
            type="target"
            :position="Position.Left"
            class="pl-pin pl-pin-var"
            :class="{ 'pl-pin-wired': data.variableNamesWithInput.includes(variable.name) }"
            :style="{ borderColor: VARIABLE_PIN_COLOR, background: data.variableNamesWithInput.includes(variable.name) ? VARIABLE_PIN_COLOR : undefined }"
            :title="`Собрать значение ${variable.name || 'переменной'} из параметра или другой переменной`"
          />
          <div class="pl-var-fields">
            <input
              :value="variable.name"
              placeholder="имя"
              class="pl-input pl-var-name nodrag"
              @input="data.on.renameVariable(index, ($event.target as HTMLInputElement).value)"
            />
            <input v-model="variable.value" placeholder="значение" class="pl-input pl-var-value nodrag" />
          </div>
          <button type="button" class="pl-icon-btn nodrag" title="Удалить переменную" @click="data.on.removeVariable(index)">
            <AppIcon name="trash" class="size-3.5" />
          </button>
          <Handle
            :id="`var-out-${index}`"
            type="source"
            :position="Position.Right"
            class="pl-pin pl-pin-var"
            :class="{ 'pl-pin-wired': data.wiredVariableNames.includes(variable.name) }"
            :style="{ borderColor: VARIABLE_PIN_COLOR, background: data.wiredVariableNames.includes(variable.name) ? VARIABLE_PIN_COLOR : undefined }"
            :title="`Значение переменной ${variable.name || 'без имени'}`"
          />
        </div>
      </div>
    </div>

    <p class="pl-params-hint">Тяните от пина ко входу данных блока — при запуске туда подставится значение.</p>
  </div>
</template>
