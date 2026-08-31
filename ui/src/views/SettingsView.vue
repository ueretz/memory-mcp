<script setup lang="ts">
import { fetchSettings, updateSetting } from '@/api/client'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'

interface FlagDescriptor {
  key: string
  title: string
  hint: string
}

const FLAGS: FlagDescriptor[] = [
  {
    key: 'feature.pipelines.enabled',
    title: 'Пайплайны',
    hint: 'Ручное построение и запуск именованных цепочек шагов из чата Claude Code.',
  },
]

const { data: settings, error, loading, reload } = useAsyncData(fetchSettings)

function isOn(key: string): boolean {
  return settings.value?.find((setting) => setting.key === key)?.value === 'true'
}

async function toggle(key: string) {
  await updateSetting(key, isOn(key) ? 'false' : 'true')
  await reload()
}
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Settings"
      title="Экспериментальные функции"
      subtitle="Выключены по умолчанию — включайте по одной, когда готовы попробовать."
    />

    <ErrorState v-if="error" :message="error" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="1" />

    <ul v-else class="space-y-3">
      <li
        v-for="flag in FLAGS"
        :key="flag.key"
        class="flex items-center justify-between gap-4 rounded-2xl border border-border bg-panel p-5"
      >
        <div class="min-w-0">
          <h2 class="text-[14.5px] font-semibold tracking-tight text-content">{{ flag.title }}</h2>
          <p class="mt-1 text-[13px] text-muted">{{ flag.hint }}</p>
        </div>
        <button
          type="button"
          role="switch"
          :aria-checked="isOn(flag.key)"
          class="relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition"
          :class="isOn(flag.key) ? 'bg-accent' : 'bg-border-strong'"
          @click="toggle(flag.key)"
        >
          <span
            class="inline-block size-4 transform rounded-full bg-white transition"
            :class="isOn(flag.key) ? 'translate-x-6' : 'translate-x-1'"
          />
        </button>
      </li>
    </ul>
  </div>
</template>
