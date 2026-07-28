<script setup lang="ts">
import { fetchSetupInfo } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import CodeBlock from '@/components/CodeBlock.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'

const { data: info, error, loading, reload } = useAsyncData(fetchSetupInfo)
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Setup"
      title="Connect memory-mcp to Claude Code"
      subtitle="Three steps: start Postgres, register the MCP server, install the skill that teaches Claude when to use it."
    />

    <ErrorState v-if="error" :message="error" @retry="reload" />
    <SkeletonRows v-else-if="loading" :rows="3" />

    <ol v-else-if="info" class="space-y-4">
      <li class="rounded-2xl border border-border bg-panel p-5">
        <div class="mb-2 flex items-center gap-2.5">
          <span
            class="flex size-6 items-center justify-center rounded-full bg-accent-soft text-[12px] font-semibold text-accent"
          >
            1
          </span>
          <h2 class="text-[14.5px] font-semibold tracking-tight text-content">Start Postgres</h2>
        </div>
        <p class="mb-3 text-[13px] text-muted">
          The MCP server and this dashboard read and write the same database — it needs to be running.
        </p>
        <CodeBlock code="docker compose up -d" />
      </li>

      <li class="rounded-2xl border border-border bg-panel p-5">
        <div class="mb-2 flex items-center gap-2.5">
          <span
            class="flex size-6 items-center justify-center rounded-full bg-accent-soft text-[12px] font-semibold text-accent"
          >
            2
          </span>
          <h2 class="text-[14.5px] font-semibold tracking-tight text-content">
            Register the MCP server
          </h2>
        </div>
        <p class="mb-3 text-[13px] text-muted">
          Run this once. It registers memory-mcp at user scope over HTTP, so it is available in every
          project — no local process to launch, Claude Code just talks to
          <code class="rounded-md border border-border bg-elevated px-1.5 py-0.5 font-mono text-[12px]">
            {{ info.mcpServerUrl }}
          </code>
          .
        </p>
        <CodeBlock :code="info.mcpAddCommand" />
      </li>

      <li class="rounded-2xl border border-border bg-panel p-5">
        <div class="mb-2 flex items-center gap-2.5">
          <span
            class="flex size-6 items-center justify-center rounded-full bg-accent-soft text-[12px] font-semibold text-accent"
          >
            3
          </span>
          <h2 class="text-[14.5px] font-semibold tracking-tight text-content">Install the skill</h2>
        </div>
        <p class="mb-3 text-[13px] text-muted">
          The skill teaches Claude when to save and search memory, how to detect the project
          automatically, and to always ask before scoping work to a task. Download it and place it at:
        </p>
        <CodeBlock :code="info.skillInstallPath" />
        <a
          href="/api/setup/skill"
          download="SKILL.md"
          class="mt-3 inline-flex items-center gap-2 rounded-lg bg-accent px-3 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover"
        >
          <AppIcon name="download" class="size-4" />
          Download SKILL.md
        </a>
      </li>
    </ol>
  </div>
</template>
