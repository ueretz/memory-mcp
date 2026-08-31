<script setup lang="ts">
withDefaults(defineProps<{ open: boolean; title: string; message: string; confirmLabel?: string; loading?: boolean }>(), {
  confirmLabel: 'Delete',
  loading: false,
})

const emit = defineEmits<{ confirm: []; cancel: [] }>()
</script>

<template>
  <div
    v-if="open"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
    @click.self="!loading && emit('cancel')"
  >
    <div class="w-full max-w-sm rounded-2xl border border-border bg-panel p-5 shadow-panel">
      <h2 class="text-[15px] font-semibold text-content">{{ title }}</h2>
      <p class="mt-2 text-[13px] leading-relaxed text-muted">{{ message }}</p>
      <div class="mt-5 flex justify-end gap-2">
        <button
          type="button"
          :disabled="loading"
          class="rounded-lg border border-border px-3 py-1.5 text-[13px] font-medium text-content transition hover:bg-elevated disabled:opacity-50"
          @click="emit('cancel')"
        >
          Cancel
        </button>
        <button
          type="button"
          :disabled="loading"
          class="rounded-lg bg-red-600 px-3 py-1.5 text-[13px] font-medium text-white transition hover:bg-red-700 disabled:opacity-50"
          @click="emit('confirm')"
        >
          {{ loading ? 'Deleting…' : confirmLabel }}
        </button>
      </div>
    </div>
  </div>
</template>
