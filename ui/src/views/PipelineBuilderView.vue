<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { useRouter } from 'vue-router'

import { createPipeline, fetchPipeline, updatePipeline, uploadPipelineAsset } from '@/api/client'
import type {
  PipelineParameterType,
  PipelineStepContentType,
  PipelineUpsertParameter,
  PipelineUpsertStep,
} from '@/api/types'
import AppIcon from '@/components/AppIcon.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'

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
  }))
}

watch(editingSlug, loadForEdit, { immediate: true })

function addParameter() {
  parameters.value.push({ name: '', label: '', type: 'STRING' as PipelineParameterType, required: false, defaultValue: null })
}

function removeParameter(index: number) {
  parameters.value.splice(index, 1)
}

function addStep() {
  steps.value.push({ title: '', contentType: 'PROMPT' as PipelineStepContentType, promptText: '', assetId: null, referenceAssetId: null })
}

function removeStep(index: number) {
  steps.value.splice(index, 1)
}

function moveStep(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= steps.value.length) return
  const [step] = steps.value.splice(index, 1)
  steps.value.splice(target, 0, step)
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
      steps: steps.value,
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
        <div v-for="(step, index) in steps" :key="index" class="mb-4 rounded-xl border border-border bg-elevated p-4">
          <div class="mb-2 flex items-center gap-2">
            <span class="text-[12px] text-faint">#{{ index + 1 }}</span>
            <input v-model="step.title" placeholder="Название шага" class="flex-1 rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content" />
            <button type="button" class="text-faint hover:text-content" :disabled="index === 0" @click="moveStep(index, -1)">↑</button>
            <button type="button" class="text-faint hover:text-content" :disabled="index === steps.length - 1" @click="moveStep(index, 1)">↓</button>
            <button type="button" class="text-faint hover:text-red-600" @click="removeStep(index)">
              <AppIcon name="trash" class="size-4" />
            </button>
          </div>
          <div class="mb-2 flex gap-3 text-[12.5px] text-muted">
            <label class="flex items-center gap-1"><input v-model="step.contentType" type="radio" value="PROMPT" /> Prompt-текст</label>
            <label class="flex items-center gap-1"><input v-model="step.contentType" type="radio" value="MD_FILE" /> .md файл</label>
          </div>
          <textarea
            v-if="step.contentType === 'PROMPT'"
            v-model="step.promptText"
            rows="3"
            placeholder="Инструкция для Claude — можно использовать {{paramName}}"
            class="w-full rounded-lg border border-border bg-panel px-2 py-1.5 text-[12.5px] text-content"
          />
          <div v-else class="text-[12.5px] text-muted">
            <input type="file" accept=".md" @change="onMdFileChosen(index, $event)" />
            <span v-if="step.assetId" class="ml-2">Загружен: asset #{{ step.assetId }}</span>
          </div>
          <div class="mt-2 text-[12.5px] text-muted">
            <label class="block">Ссылочный файл (необязательно, например html-шаблон отчёта):</label>
            <input type="file" @change="onReferenceFileChosen(index, $event)" />
            <span v-if="step.referenceAssetId" class="ml-2">Загружен: asset #{{ step.referenceAssetId }}</span>
          </div>
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
