<script setup lang="ts">
import { ref, toRef, watch, computed } from 'vue'
import { useRouter } from 'vue-router'

import { createPipeline, fetchPipeline, updatePipeline } from '@/api/client'
import type { PipelineParameterType, PipelineUpsertParameter, PipelineUpsertParameterLink, PipelineUpsertStep } from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'

const props = defineProps<{ slug?: string }>()
const editingSlug = toRef(props, 'slug')
const isEditing = computed(() => !!editingSlug.value)

const router = useRouter()

const slug = ref('')
const name = ref('')
const description = ref('')
const parameters = ref<PipelineUpsertParameter[]>([])
// Steps are never edited on this screen - assembling the board happens on the separate canvas
// screen (PipelineBoardView). We still have to carry them through save() unchanged, because
// updatePipeline replaces the whole pipeline definition - submitting an empty list here would
// silently wipe out every step the author already built on the board.
const steps = ref<PipelineUpsertStep[]>([])
// Parameter -> step wires are drawn on the board; round-trip them here, dropping any whose
// parameter was renamed or removed on this screen (the backend rejects links to unknown names).
const parameterLinks = ref<PipelineUpsertParameterLink[]>([])
const existingProjectScope = ref<string | null>(null)
const saving = ref(false)
const saveError = ref<string | null>(null)

async function loadForEdit() {
  if (!editingSlug.value) return
  const pipeline = await fetchPipeline(editingSlug.value)
  slug.value = pipeline.slug
  existingProjectScope.value = pipeline.projectScope
  parameterLinks.value = pipeline.parameterLinks.map((l) => ({ token: l.token, parameterName: l.parameterName, targetStepIndex: l.targetStepOrderIndex }))
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
    conditionOperator: s.conditionOperator,
    conditionValue: s.conditionValue,
  }))
}

watch(editingSlug, loadForEdit, { immediate: true })

function addParameter() {
  parameters.value.push({ name: '', label: '', type: 'STRING' as PipelineParameterType, required: false, defaultValue: null })
}

function removeParameter(index: number) {
  parameters.value.splice(index, 1)
}

async function save() {
  saving.value = true
  saveError.value = null
  try {
    const request = {
      slug: slug.value,
      name: name.value,
      description: description.value || null,
      // Pipelines are shared across projects; a new one is saved without a scope, an existing one
      // keeps whatever scope it was created with (informational only, never used for filtering).
      projectScope: isEditing.value ? existingProjectScope.value : null,
      parameters: parameters.value,
      steps: steps.value,
      parameterLinks: parameterLinks.value.filter((link) => parameters.value.some((p) => p.name === link.parameterName)),
    }
    const result = isEditing.value ? await updatePipeline(editingSlug.value!, request) : await createPipeline(request)
    // Both creating a new pipeline and editing an existing one's metadata land on the board next -
    // metadata here is just the setup step, the board is where the pipeline actually gets built.
    await router.push({ name: 'pipeline-board', params: { slug: result.slug } })
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
      :title="isEditing ? 'Настройки пайплайна' : 'Новый пайплайн'"
      :subtitle="isEditing ? 'Название, описание и входные параметры — сборка шагов на доске' : 'Задайте название и входные параметры, дальше — сборка шагов на доске'"
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

      <button
        type="button"
        :disabled="saving"
        class="rounded-lg bg-accent px-4 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover disabled:opacity-50"
        @click="save"
      >
        {{ saving ? 'Сохранение…' : (isEditing ? 'Сохранить и открыть доску' : 'Создать и открыть доску') }}
      </button>
    </div>
  </div>
</template>
