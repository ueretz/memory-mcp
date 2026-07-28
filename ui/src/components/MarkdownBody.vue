<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

import { renderMarkdown } from '@/lib/markdown'

const props = defineProps<{
  content: string | null
  /** Maps a [[wiki-link]] name to an in-app path, or null when the entry is unknown. */
  resolveLink?: (name: string) => string | null
}>()

const router = useRouter()

const html = computed(() => renderMarkdown(props.content, props.resolveLink ?? (() => null)))

/** Keeps internal links inside the SPA instead of triggering a full page load. */
function onClick(event: MouseEvent) {
  const anchor = (event.target as HTMLElement | null)?.closest('a')
  const href = anchor?.getAttribute('href')
  if (!anchor || !href || anchor.target === '_blank' || event.metaKey || event.ctrlKey) {
    return
  }
  if (href.startsWith('/')) {
    event.preventDefault()
    void router.push(href)
  }
}
</script>

<template>
  <!-- eslint-disable-next-line vue/no-v-html -- sanitised in renderMarkdown -->
  <div class="md-body" @click="onClick" v-html="html" />
</template>
