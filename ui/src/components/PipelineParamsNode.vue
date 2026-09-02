<script setup lang="ts">
import { Handle, Position, type NodeProps } from '@vue-flow/core'

import type { PipelineUpsertParameter } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import { PARAM_PIN_COLORS } from '@/lib/pipelineBoard'

/**
 * The board's fixed "input parameters" node: one row per pipeline parameter with a source pin
 * (`param-out-<i>`, keyed by index like every other pin) that wires the parameter's run-time
 * value into a step's data input. Parameters themselves are edited on the settings screen; the
 * node only shows them and links there.
 */
defineProps<
  NodeProps<{
    parameters: PipelineUpsertParameter[]
    wiredNames: string[]
    settingsTo: { name: string; params: Record<string, string> }
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
        <span class="pl-params-title">Параметры запуска</span>
      </div>
      <RouterLink :to="data.settingsTo" class="pl-icon-btn pl-icon-btn-neutral nodrag" title="Изменить параметры в настройках">
        <AppIcon name="cog" class="size-3.5" />
      </RouterLink>
    </header>

    <div v-if="data.parameters.length === 0" class="pl-params-empty">
      <p>Параметров пока нет.</p>
      <RouterLink :to="data.settingsTo" class="pl-text-btn nodrag">Добавить в настройках</RouterLink>
    </div>

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
          :class="{ 'pl-pin-wired': data.wiredNames.includes(parameter.name) }"
          :style="{ borderColor: PARAM_PIN_COLORS[parameter.type], background: data.wiredNames.includes(parameter.name) ? PARAM_PIN_COLORS[parameter.type] : undefined }"
          :title="`Значение параметра ${parameter.name}`"
        />
      </div>
    </div>
    <p class="pl-params-hint">Тяните от пина ко входу данных блока — при запуске туда подставится значение.</p>
  </div>
</template>
