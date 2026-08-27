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
          <h2 class="text-[14.5px] font-semibold tracking-tight text-content">Install the skills</h2>
        </div>
        <p class="mb-4 text-[13px] text-muted">
          Three independent skills, each with one job - download each and unzip/place it at the
          path shown (single-file skills download as a plain <code class="rounded-md border border-border bg-elevated px-1 py-0.5 font-mono text-[11px]">.md</code>,
          skills with an asset folder download as a <code class="rounded-md border border-border bg-elevated px-1 py-0.5 font-mono text-[11px]">.zip</code>).
        </p>
        <ul class="space-y-3">
          <li v-for="skill in info.skills" :key="skill.id" class="rounded-xl border border-border bg-elevated p-4">
            <h3 class="font-mono text-[13px] font-semibold text-content">{{ skill.id }}</h3>
            <p class="mt-1 mb-3 text-[12.5px] text-muted">{{ skill.description }}</p>
            <CodeBlock :code="skill.installPath" />
            <a
              :href="skill.downloadUrl"
              class="mt-3 inline-flex items-center gap-2 rounded-lg bg-accent px-3 py-2 text-[13px] font-medium text-accent-fg transition hover:bg-accent-hover"
            >
              <AppIcon name="download" class="size-4" />
              Download {{ skill.title }}
            </a>
          </li>
        </ul>
      </li>
    </ol>
  </div>
</template>
