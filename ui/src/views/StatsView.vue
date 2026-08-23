<script setup lang="ts">
import { computed } from 'vue'

import { fetchStats } from '@/api/client'
import type { DailyActivity, MemoryType } from '@/api/types'
import ConstellationField from '@/components/ConstellationField.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryRow from '@/components/EntryRow.vue'
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
}

const { data: stats, error, loading, reload } = useAsyncData(() => fetchStats(null, null, 30))

const CHART_WIDTH = 640
const CHART_HEIGHT = 140

function points(activity: DailyActivity[]): string {
  if (activity.length === 0) {
    return ''
  }
  const max = Math.max(...activity.map((d) => d.count), 1)
  const stepX = activity.length > 1 ? CHART_WIDTH / (activity.length - 1) : 0
  return activity
    .map((d, i) => `${i * stepX},${CHART_HEIGHT - (d.count / max) * (CHART_HEIGHT - 8) - 4}`)
    .join(' ')
}

const linePoints = computed(() => (stats.value ? points(stats.value.activityByDay) : ''))
const areaPoints = computed(() =>
  stats.value && stats.value.activityByDay.length > 0
    ? `0,${CHART_HEIGHT} ${linePoints.value} ${CHART_WIDTH},${CHART_HEIGHT}`
    : '',
)

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
          v-if="stats.activityByDay.length === 0"
          icon="sparkles"
          title="No activity yet"
          hint="Once Claude saves or reads memory, activity shows up here."
          class="relative mt-6 border-0 bg-transparent"
        />
        <svg
          v-else
          class="relative mt-4 h-[140px] w-full"
          :viewBox="`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`"
          preserveAspectRatio="none"
        >
          <polygon :points="areaPoints" class="fill-accent/10" />
          <polyline
            :points="linePoints"
            fill="none"
            class="stroke-accent"
            stroke-width="2"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </section>

      <section class="mb-6 rounded-2xl border border-border bg-panel p-5">
        <h2 class="mb-4 text-[13px] font-semibold tracking-wide text-content uppercase">By type</h2>
        <EmptyState v-if="stats.byType.length === 0" icon="document" title="No entries yet" />
        <div v-else class="space-y-2.5">
          <div v-for="row in stats.byType" :key="row.type" class="flex items-center gap-3">
            <TypeBadge :type="row.type" variant="dot" />
            <span class="w-20 shrink-0 text-[12.5px] font-medium text-content">{{ row.type }}</span>
            <div class="h-2 flex-1 overflow-hidden rounded-full bg-elevated">
              <div
                class="h-full rounded-full"
                :class="BAR_FILL[row.type]"
                :style="{ width: `${(row.count / maxTypeCount) * 100}%` }"
              />
            </div>
            <span class="w-8 shrink-0 text-right text-[12.5px] tabular-nums text-muted">{{ row.count }}</span>
          </div>
        </div>
      </section>

      <section class="rounded-2xl border border-border bg-panel p-5">
        <h2 class="mb-4 text-[13px] font-semibold tracking-wide text-content uppercase">Most accessed</h2>
        <EmptyState v-if="stats.topEntries.length === 0" icon="graph" title="Nothing accessed yet" />
        <div v-else class="space-y-2">
          <EntryRow
            v-for="entry in stats.topEntries"
            :key="entry.name"
            :entry="{
              name: entry.name,
              type: entry.type,
              description: entry.description,
              projectScope: entry.projectScope,
              taskKey: entry.taskKey,
              filePath: null,
              updatedAt: '',
            }"
            :access-count="entry.accessCount"
          />
        </div>
      </section>
    </template>
  </div>
</template>
