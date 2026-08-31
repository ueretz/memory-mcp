<script setup lang="ts">
import { computed, ref, toRef } from 'vue'
import { useRouter } from 'vue-router'

import { deleteFolder, fetchEntries, fetchFolder, fetchFolders } from '@/api/client'
import AppIcon from '@/components/AppIcon.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import EmptyState from '@/components/EmptyState.vue'
import EntryCard from '@/components/EntryCard.vue'
import ErrorState from '@/components/ErrorState.vue'
import FolderCard from '@/components/FolderCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import SkeletonRows from '@/components/SkeletonRows.vue'
import { useAsyncData } from '@/composables/useAsyncData'
import { folderLocation, projectLocation, taskLocation } from '@/lib/links'

const props = defineProps<{ project: string; folder: string }>()

const project = toRef(props, 'project')
const folderName = toRef(props, 'folder')

const { data: folder, error: folderError, loading: folderLoading } = useAsyncData(
  () => fetchFolder(folderName.value),
  [folderName],
)

const { data: subfolders, loading: subfoldersLoading } = useAsyncData(
  () => fetchFolders(project.value, folder.value?.taskKey ?? null, folderName.value),
  [folderName, folder],
)

const { data: entries, error: entriesError, loading: entriesLoading, reload } = useAsyncData(
  () => fetchEntries(project.value, folder.value?.taskKey ?? null, null, folderName.value),
  [folderName, folder],
)

const backLink = computed(() => {
  if (!folder.value) {
    return projectLocation(project.value)
  }
  if (folder.value.parentFolder) {
    return folderLocation(project.value, folder.value.parentFolder)
  }
  return folder.value.taskKey ? taskLocation(project.value, folder.value.taskKey) : projectLocation(project.value)
})

const router = useRouter()
const showDeleteConfirm = ref(false)
const deleting = ref(false)
const deleteError = ref<string | null>(null)

const deleteMessage = computed(() => {
  const count = subfolders.value?.length ?? 0
  const extra = count > 0 ? ` ${count} subfolder${count === 1 ? '' : 's'} will also be deleted.` : ''
  return `Delete folder "${folderName.value}"?${extra} Entries inside are moved to the root, not deleted. This can't be undone.`
})

async function confirmDelete() {
  deleting.value = true
  deleteError.value = null
  try {
    await deleteFolder(folderName.value)
    await router.push(backLink.value)
  } catch (cause) {
    deleteError.value = cause instanceof Error ? cause.message : String(cause)
  } finally {
    deleting.value = false
    showDeleteConfirm.value = false
  }
}
</script>

<template>
  <div>
    <ErrorState v-if="folderError" :message="folderError" />
    <template v-else>
      <PageHeader eyebrow="Folder" :title="folder?.name ?? folderName" :subtitle="folder?.description">
        <template #actions>
          <button
            type="button"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-red-500/50 hover:text-red-600"
            @click="showDeleteConfirm = true"
          >
            <AppIcon name="trash" class="size-4" />
            Delete
          </button>
          <RouterLink
            :to="backLink"
            class="inline-flex items-center gap-2 rounded-lg border border-border bg-panel px-3 py-2 text-[13px] font-medium text-content transition hover:border-accent/40 hover:text-accent"
          >
            <AppIcon name="arrowLeft" class="size-4" />
            Back
          </RouterLink>
        </template>
      </PageHeader>

      <SkeletonRows v-if="folderLoading" :rows="1" class="mb-6" />

      <section class="mb-9">
        <h2 class="mb-3 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
          <AppIcon name="folder" class="size-4 text-faint" />
          Folders
        </h2>
        <SkeletonRows v-if="subfoldersLoading" :rows="2" />
        <EmptyState v-else-if="!subfolders?.length" icon="folder" title="No subfolders" />
        <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <FolderCard v-for="sub in subfolders" :key="sub.name" :folder="sub" :project-scope="project" />
        </div>
      </section>

      <section>
        <h2 class="mb-3 flex items-center gap-2 text-[13px] font-semibold tracking-wide text-content uppercase">
          <AppIcon name="document" class="size-4 text-faint" />
          Entries
        </h2>
        <ErrorState v-if="entriesError" :message="entriesError" @retry="reload" />
        <SkeletonRows v-else-if="entriesLoading" :rows="3" />
        <EmptyState v-else-if="!entries?.length" icon="document" title="No entries in this folder yet" />
        <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <EntryCard v-for="entry in entries" :key="entry.name" :entry="entry" />
        </div>
      </section>
    </template>

    <ConfirmDialog
      :open="showDeleteConfirm"
      title="Delete this folder?"
      :message="deleteMessage"
      :loading="deleting"
      @confirm="confirmDelete"
      @cancel="showDeleteConfirm = false"
    />
    <p v-if="deleteError" class="mt-3 text-[12.5px] text-red-600">{{ deleteError }}</p>
  </div>
</template>
