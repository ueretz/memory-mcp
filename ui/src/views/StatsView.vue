<script setup lang="ts">
import { computed, ref } from 'vue'

import { fetchStats } from '@/api/client'
import type { DailyActivity, MemoryType } from '@/api/types'
import ConstellationField from '@/components/ConstellationField.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryCard from '@/components/EntryCard.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import TypeBadge from '@/components/TypeBadge.vue'
import { useAsyncData } from '@/composables/useAsyncData'

// Written out in full so Tailwind can see every class it needs to generate.
const BAR_FILL: Record<MemoryType, string> = {
  USER: 'bg-type-user',
  FEEDBACK: 'bg-type-feedback',
  PROJECT: 'bg-type-project',
  REFERENCE: 'bg-type-reference',
  LOCATION: 'bg-type-location',
  REPORT: 'bg-type-report',
}

const DAYS = 30
const { data: stats, error, loading, reload } = useAsyncData(() => fetchStats(null, null, DAYS))

const CHART_WIDTH = 640
const CHART_HEIGHT = 120

/**
 * The API only returns rows for days that actually had activity. Spacing those evenly by
 * array index (instead of by real calendar distance) would visually compress a 20-day gap
 * into "right next to each other" - so every day in the window is filled in here, zero or not,
 * and the x-axis always represents true daily cadence.
 */
const filledActivity = computed<DailyActivity[]>(() => {
  const byDay = new Map((stats.value?.activityByDay ?? []).map((d) => [d.day, d.count]))
  const today = new Date()
  const result: DailyActivity[] = []
  for (let i = DAYS - 1; i >= 0; i--) {
    const date = new Date(today)
    date.setUTCDate(date.getUTCDate() - i)
    const key = date.toISOString().slice(0, 10)
    result.push({ day: key, count: byDay.get(key) ?? 0 })
  }
  return result
})

const maxDaily = computed(() => Math.max(...filledActivity.value.map((d) => d.count), 1))

function xAt(index: number): number {
  return filledActivity.value.length > 1 ? (index / (filledActivity.value.length - 1)) * CHART_WIDTH : 0
}

function yAt(count: number): number {
  return CHART_HEIGHT - (count / maxDaily.value) * (CHART_HEIGHT - 10) - 5
}

const linePoints = computed(() =>
  filledActivity.value.map((day, index) => `${xAt(index)},${yAt(day.count)}`).join(' '),
)
const areaPoints = computed(() =>
  filledActivity.value.length > 0
    ? `0,${CHART_HEIGHT} ${linePoints.value} ${CHART_WIDTH},${CHART_HEIGHT}`
    : '',
)

const GRID_LINES = [0, 0.5, 1]

function formatDay(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  })
}

const hoverIndex = ref<number | null>(null)

function onChartMove(event: MouseEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  if (rect.width === 0) {
    return
  }
  const fraction = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width))
  hoverIndex.value = Math.round(fraction * (filledActivity.value.length - 1))
}

const hoverDay = computed(() => (hoverIndex.value !== null ? filledActivity.value[hoverIndex.value] : null))
const tooltipLeftPct = computed(() =>
  hoverIndex.value !== null ? (xAt(hoverIndex.value) / CHART_WIDTH) * 100 : 0,
)

const totalTypeCount = computed(() => stats.value?.byType.reduce((sum, row) => sum + row.count, 0) ?? 0)
const maxTypeCount = computed(() => Math.max(...(stats.value?.byType.map((t) => t.count) ?? [1]), 1))
</script>

<template>
  <div>
    <PageHeader eyebrow="Overview" title="Statistics" subtitle="How memory is being used across every project." />

    <ErrorState v-if="error" :message="error" class="mb-6" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="4" />

    <template v-else-if="stats">
      <section class="group relative mb-6 overflow-hidden rounded-2xl border border-border bg-panel p-6">
        <ConstellationField class="opacity-40" />
        <div class="relative flex flex-wrap gap-8">
          <div>
            <p class="text-3xl font-semibold tracking-tight text-content tabular-nums">
              {{ stats.totals.totalEvents }}
            </p>
            <p class="mt-1 text-[12px] font-semibold tracking-wide text-faint uppercase">Events · last 30 days</p>
          </div>
          <div>
            <p class="text-3xl font-semibold tracking-tight text-content tabular-nums">
              {{ stats.totals.totalEntries }}
            </p>
            <p class="mt-1 text-[12px] font-semibold tracking-wide text-faint uppercase">Entries stored</p>
          </div>
        </div>

        <EmptyState
          v-if="stats.totals.totalEvents === 0"
          icon="sparkles"
          title="No activity yet"
          hint="Once Claude saves or reads memory, activity shows up here."
          class="relative mt-6 border-0 bg-transparent"
        />
        <div v-else class="relative mt-6 flex gap-2">
          <div class="flex w-7 shrink-0 flex-col justify-between py-0.5 text-right text-[10px] text-faint tabular-nums">
            <span>{{ maxDaily }}</span>
            <span>0</span>
          </div>

          <div
            class="relative min-w-0 flex-1"
            @mousemove="onChartMove"
            @mouseleave="hoverIndex = null"
          >
            <div
              v-if="hoverDay"
              class="pointer-events-none absolute z-10 -translate-x-1/2 -translate-y-full whitespace-nowrap rounded-lg border border-border bg-elevated px-2.5 py-1.5 text-[11px] shadow-panel"
              :style="{ left: `${tooltipLeftPct}%`, top: '-6px' }"
            >
              <div class="font-semibold text-content tabular-nums">{{ hoverDay.count }} {{ hoverDay.count === 1 ? 'event' : 'events' }}</div>
              <div class="text-faint">{{ formatDay(hoverDay.day) }}</div>
            </div>

            <svg
              class="h-[120px] w-full overflow-visible"
              :viewBox="`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`"
              preserveAspectRatio="none"
            >
              <line
                v-for="fraction in GRID_LINES"
                :key="fraction"
                x1="0"
                :x2="CHART_WIDTH"
                :y1="fraction * CHART_HEIGHT"
                :y2="fraction * CHART_HEIGHT"
                class="stroke-border"
                stroke-width="1"
                vector-effect="non-scaling-stroke"
              />
              <polygon :points="areaPoints" class="fill-accent/10" />
              <polyline
                :points="linePoints"
                fill="none"
                class="stroke-accent"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                vector-effect="non-scaling-stroke"
              />
              <line
                v-if="hoverIndex !== null"
                :x1="xAt(hoverIndex)"
                :x2="xAt(hoverIndex)"
                y1="0"
                :y2="CHART_HEIGHT"
                class="stroke-border-strong"
                stroke-width="1"
                stroke-dasharray="3 3"
                vector-effect="non-scaling-stroke"
              />
              <circle
                v-if="hoverDay"
                :cx="xAt(hoverIndex!)"
                :cy="yAt(hoverDay.count)"
                r="3.5"
                class="fill-accent"
                vector-effect="non-scaling-stroke"
              />
            </svg>

            <div class="mt-1.5 flex justify-between text-[10px] text-faint">
              <span>{{ formatDay(filledActivity[0].day) }}</span>
              <span>{{ formatDay(filledActivity[filledActivity.length - 1].day) }}</span>
            </div>
          </div>
        </div>
      </section>

      <section class="mb-6 rounded-2xl border border-border bg-panel p-5">
        <h2 class="mb-4 text-[13px] font-semibold tracking-wide text-content uppercase">By type</h2>
        <EmptyState v-if="stats.byType.length === 0" icon="document" title="No entries yet" />
        <div v-else class="space-y-3">
          <div v-for="row in stats.byType" :key="row.type" class="flex items-center gap-3">
            <TypeBadge :type="row.type" variant="dot" />
            <span class="w-20 shrink-0 text-[12.5px] font-medium text-content">{{ row.type }}</span>
            <div class="h-2.5 flex-1 overflow-hidden rounded-full bg-elevated">
              <div
                class="h-full rounded-full transition-all"
                :class="BAR_FILL[row.type]"
                :style="{ width: `${(row.count / maxTypeCount) * 100}%` }"
              />
            </div>
            <span class="w-20 shrink-0 text-right text-[12.5px] tabular-nums text-muted">
              {{ row.count }}
              <span class="text-faint">({{ totalTypeCount ? Math.round((row.count / totalTypeCount) * 100) : 0 }}%)</span>
            </span>
          </div>
        </div>
      </section>

      <section class="rounded-2xl border border-border bg-panel p-5">
        <h2 class="mb-4 text-[13px] font-semibold tracking-wide text-content uppercase">Most accessed</h2>
        <EmptyState v-if="stats.topEntries.length === 0" icon="graph" title="Nothing accessed yet" />
        <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <EntryCard
            v-for="entry in stats.topEntries"
            :key="entry.name"
            :entry="{
              name: entry.name,
              type: entry.type,
              description: entry.description,
              projectScope: entry.projectScope,
              taskKey: entry.taskKey,
              filePath: null,
              createdBy: null,
              updatedAt: '',
            }"
            show-scope
            :access-count="entry.accessCount"
          />
        </div>
      </section>
    </template>
  </div>
</template>
