<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { searchEntries } from '@/api/client'
import { MEMORY_TYPES, type MemoryEntrySummary, type MemoryType } from '@/api/types'
import { entryLocation } from '@/lib/links'

import AppIcon from './AppIcon.vue'
import TypeBadge from './TypeBadge.vue'

const open = defineModel<boolean>('open', { required: true })

const router = useRouter()
const input = ref<HTMLInputElement | null>(null)
const query = ref('')
const type = ref<MemoryType | null>(null)
const results = ref<MemoryEntrySummary[]>([])
const highlighted = ref(0)
const loading = ref(false)

let debounce: ReturnType<typeof setTimeout> | undefined
let sequence = 0

async function run() {
  const text = query.value.trim()
  if (!text) {
    results.value = []
    loading.value = false
    return
  }
  const current = ++sequence
  loading.value = true
  try {
    const found = await searchEntries(text, type.value)
    if (current === sequence) {
      results.value = found
      highlighted.value = 0
    }
  } catch {
    if (current === sequence) {
      results.value = []
    }
  } finally {
    if (current === sequence) {
      loading.value = false
    }
  }
}

watch([query, type], () => {
  clearTimeout(debounce)
  debounce = setTimeout(() => void run(), 180)
})

watch(open, async (isOpen) => {
  document.body.style.overflow = isOpen ? 'hidden' : ''
  if (isOpen) {
    await nextTick()
    input.value?.focus()
    input.value?.select()
  }
})

function go(entry: MemoryEntrySummary) {
  const to = entryLocation(entry)
  if (!to) {
    return
  }
  open.value = false
  void router.push(to)
}

function onKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    open.value = !open.value
    return
  }
  if (!open.value) {
    return
  }
  if (event.key === 'Escape') {
    open.value = false
  } else if (event.key === 'ArrowDown') {
    event.preventDefault()
    highlighted.value = Math.min(highlighted.value + 1, results.value.length - 1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    highlighted.value = Math.max(highlighted.value - 1, 0)
  } else if (event.key === 'Enter') {
    const entry = results.value[highlighted.value]
    if (entry) {
      event.preventDefault()
      go(entry)
    }
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition duration-150 ease-out"
      enter-from-class="opacity-0"
      leave-active-class="transition duration-100 ease-in"
      leave-to-class="opacity-0"
    >
      <div v-if="open" class="fixed inset-0 z-[60] bg-black/45 backdrop-blur-[3px]" @click="open = false">
        <div
          class="mx-auto mt-[12vh] w-[min(38rem,calc(100vw-2rem))] overflow-hidden rounded-2xl border border-border bg-panel shadow-2xl"
          @click.stop
        >
          <div class="flex items-center gap-3 border-b border-border px-4">
            <AppIcon name="search" class="size-4 shrink-0 text-faint" />
            <input
              ref="input"
              v-model="query"
              type="text"
              placeholder="Search every memory entry…"
              class="h-13 w-full bg-transparent py-4 text-[14px] text-content outline-none placeholder:text-faint"
            />
            <kbd
              class="shrink-0 rounded-md border border-border bg-elevated px-1.5 py-0.5 text-[10.5px] font-medium text-faint"
            >
              esc
            </kbd>
          </div>

          <div class="flex flex-wrap items-center gap-1.5 border-b border-border px-4 py-2.5">
            <button
              type="button"
              class="rounded-full px-2.5 py-1 text-[11.5px] font-medium transition"
              :class="
                type === null
                  ? 'bg-accent-soft text-accent'
                  : 'text-muted hover:bg-elevated hover:text-content'
              "
              @click="type = null"
            >
              All
            </button>
            <button
              v-for="memoryType in MEMORY_TYPES"
              :key="memoryType"
              type="button"
              class="rounded-full px-2.5 py-1 text-[11.5px] font-medium transition"
              :class="
                type === memoryType
                  ? 'bg-accent-soft text-accent'
                  : 'text-muted hover:bg-elevated hover:text-content'
              "
              @click="type = memoryType"
            >
              {{ memoryType }}
            </button>
          </div>

          <div class="max-h-[52vh] overflow-y-auto p-2">
            <p v-if="loading" class="px-3 py-6 text-center text-[13px] text-faint">Searching…</p>

            <p v-else-if="!query.trim()" class="px-3 py-6 text-center text-[13px] text-faint">
              Type to search names, descriptions and content.
            </p>

            <p v-else-if="results.length === 0" class="px-3 py-6 text-center text-[13px] text-faint">
              No entries match “{{ query }}”.
            </p>

            <button
              v-for="(entry, index) in results"
              :key="entry.name"
              type="button"
              class="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition"
              :class="index === highlighted ? 'bg-accent-soft' : 'hover:bg-elevated'"
              @mouseenter="highlighted = index"
              @click="go(entry)"
            >
              <TypeBadge :type="entry.type" variant="dot" />
              <span class="min-w-0 flex-1">
                <span class="block truncate text-[13.5px] font-medium text-content">{{ entry.name }}</span>
                <span class="block truncate text-[12px] text-muted">{{ entry.description }}</span>
              </span>
              <span
                v-if="entry.projectScope"
                class="hidden max-w-[10rem] shrink-0 truncate font-mono text-[11px] text-faint sm:block"
              >
                {{ entry.projectScope }}{{ entry.taskKey ? ` / ${entry.taskKey}` : '' }}
              </span>
              <AppIcon
                v-if="index === highlighted"
                name="enter"
                class="hidden size-3.5 shrink-0 text-accent sm:block"
              />
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
