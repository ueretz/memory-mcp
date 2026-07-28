<script setup lang="ts">
import { ref } from 'vue'

import AppIcon from './AppIcon.vue'

const props = defineProps<{ code: string }>()

const copied = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined

async function copy() {
  try {
    await navigator.clipboard.writeText(props.code)
  } catch {
    return
  }
  copied.value = true
  clearTimeout(timer)
  timer = setTimeout(() => {
    copied.value = false
  }, 1600)
}
</script>

<template>
  <div class="group relative overflow-hidden rounded-xl border border-border bg-elevated">
    <pre class="overflow-x-auto px-4 py-3.5 pr-24 font-mono text-[12.5px] leading-relaxed text-content"><code>{{ code }}</code></pre>
    <button
      type="button"
      class="absolute top-2.5 right-2.5 inline-flex items-center gap-1.5 rounded-lg border border-border bg-panel px-2.5 py-1.5 text-[11.5px] font-medium text-muted opacity-0 transition group-hover:opacity-100 focus-visible:opacity-100 hover:border-border-strong hover:text-content"
      :class="{ 'text-type-user opacity-100': copied }"
      @click="copy"
    >
      <AppIcon :name="copied ? 'check' : 'copy'" class="size-3.5" />
      {{ copied ? 'Copied' : 'Copy' }}
    </button>
  </div>
</template>
