import { ref, shallowRef, watch, type WatchSource } from 'vue'

/**
 * Loads data on mount and whenever `deps` change, keeping only the newest response
 * so fast navigation can't leave a stale result on screen.
 */
export function useAsyncData<T>(loader: () => Promise<T>, deps: WatchSource[] = []) {
  const data = shallowRef<T | null>(null)
  const error = ref<string | null>(null)
  const loading = ref(true)
  let sequence = 0

  async function reload() {
    const current = ++sequence
    loading.value = true
    error.value = null
    try {
      const result = await loader()
      if (current === sequence) {
        data.value = result
      }
    } catch (cause) {
      if (current === sequence) {
        data.value = null
        error.value = cause instanceof Error ? cause.message : String(cause)
      }
    } finally {
      if (current === sequence) {
        loading.value = false
      }
    }
  }

  if (deps.length > 0) {
    watch(deps, () => void reload())
  }
  void reload()

  return { data, error, loading, reload }
}
