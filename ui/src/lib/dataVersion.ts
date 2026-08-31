import { ref } from 'vue'

/**
 * Bumped after any successful delete. Components that fetch data once outside <RouterView>
 * (so they never remount on navigation, e.g. AppSidebar's project list) include this in their
 * useAsyncData deps to refetch when something elsewhere in the app was deleted.
 */
export const dataVersion = ref(0)

export function bumpDataVersion(): void {
  dataVersion.value++
}
