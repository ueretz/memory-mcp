<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { fetchStats } from '@/api/client'
import { useAsyncData } from '@/composables/useAsyncData'
import { useTheme } from '@/composables/useTheme'

import AppIcon from './AppIcon.vue'
import BreadcrumbBar from './BreadcrumbBar.vue'

defineEmits<{ 'toggle-sidebar': []; 'open-search': [] }>()

const { isDark, toggle } = useTheme()
const shortcut = ref('Ctrl K')

onMounted(() => {
  if (navigator.platform.toLowerCase().includes('mac')) {
    shortcut.value = '⌘ K'
  }
})

const { data: pulse } = useAsyncData(() => fetchStats(null, null, 7))
const eventCount = computed(() => pulse.value?.totals.totalEvents ?? null)
</script>

<template>
  <header
    class="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-border bg-bg/80 px-4 backdrop-blur-md sm:px-6 lg:px-10"
  >
    <button
      type="button"
      class="-ml-1 rounded-lg p-1.5 text-muted transition hover:bg-elevated hover:text-content lg:hidden"
      aria-label="Open navigation"
      @click="$emit('toggle-sidebar')"
    >
      <AppIcon name="menu" class="size-5" />
    </button>

    <BreadcrumbBar class="min-w-0 flex-1" />

    <RouterLink
      v-if="eventCount !== null"
      :to="{ name: 'stats' }"
      class="hidden items-center gap-1.5 rounded-full bg-elevated px-2.5 py-1 text-[11.5px] font-medium text-muted transition hover:text-content md:inline-flex"
    >
      <span class="size-1.5 rounded-full bg-accent" />
      {{ eventCount }} {{ eventCount === 1 ? 'event' : 'events' }} this week
    </RouterLink>

    <button
      type="button"
      class="group flex items-center gap-2 rounded-lg border border-border bg-panel py-1.5 pr-2 pl-2.5 text-[13px] text-muted transition hover:border-border-strong hover:text-content"
      @click="$emit('open-search')"
    >
      <AppIcon name="search" class="size-4" />
      <span class="hidden sm:inline">Search</span>
      <kbd
        class="hidden rounded-md border border-border bg-elevated px-1.5 py-0.5 font-sans text-[10.5px] font-medium text-faint sm:inline"
      >
        {{ shortcut }}
      </kbd>
    </button>

    <button
      type="button"
      class="rounded-lg border border-border bg-panel p-2 text-muted transition hover:border-border-strong hover:text-content"
      :aria-label="isDark ? 'Switch to light theme' : 'Switch to dark theme'"
      @click="toggle"
    >
      <AppIcon :name="isDark ? 'sun' : 'moon'" class="size-4" />
    </button>
  </header>
</template>
