<script setup lang="ts">
import { ref } from 'vue'

import type { AgentTaskSummary } from '@/api/types'
import { relativeTime } from '@/lib/format'

import AgentTaskTypeBadge from './AgentTaskTypeBadge.vue'
import AppIcon from './AppIcon.vue'
import MarkdownBody from './MarkdownBody.vue'

defineProps<{
  agentTask: AgentTaskSummary
  resolveLink?: (name: string) => string | null
  dependsOnTitle?: string | null
}>()

const expanded = ref(false)
</script>

<template>
  <div class="flex min-w-0 flex-col gap-2 rounded-xl border border-border bg-panel p-3">
    <button type="button" class="flex items-center justify-between gap-2 text-left" @click="expanded = !expanded">
      <span class="flex min-w-0 items-center gap-2">
        <AgentTaskTypeBadge :type="agentTask.type" />
        <span class="truncate text-[13px] font-medium text-content">{{ agentTask.title }}</span>
      </span>
      <AppIcon name="chevron" class="size-3.5 shrink-0 text-faint transition" :class="{ 'rotate-90': expanded }" />
    </button>
    <p v-if="dependsOnTitle" class="flex items-center gap-1 text-[11px] text-faint">
      <AppIcon name="link" class="size-3" />
      Depends on: {{ dependsOnTitle }}
    </p>
    <MarkdownBody
      v-if="expanded && agentTask.description"
      :content="agentTask.description"
      :resolve-link="resolveLink"
    />
    <p v-else-if="agentTask.description" class="line-clamp-2 text-[12px] text-muted">{{ agentTask.description }}</p>
    <time class="text-[11px] text-faint" :datetime="agentTask.updatedAt">{{ relativeTime(agentTask.updatedAt) }}</time>
  </div>
</template>
