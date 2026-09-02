import { onBeforeUnmount, onMounted, watch, type Ref } from 'vue'

/**
 * Calls `tick` every `intervalMs` while `active` is true and the tab is visible. Used to keep
 * run status live without a push channel - the server has no SSE/WebSocket, so the dashboard
 * re-fetches while something is still RUNNING and stops as soon as nothing is.
 */
export function usePolling(tick: () => unknown, active: Ref<boolean>, intervalMs = 3000) {
  let timer: ReturnType<typeof setInterval> | null = null

  function stop() {
    if (timer) clearInterval(timer)
    timer = null
  }

  function start() {
    stop()
    timer = setInterval(() => {
      if (document.visibilityState === 'visible') void tick()
    }, intervalMs)
  }

  function sync() {
    if (active.value) start()
    else stop()
  }

  function onVisibility() {
    if (document.visibilityState === 'visible' && active.value) void tick()
  }

  onMounted(() => {
    sync()
    document.addEventListener('visibilitychange', onVisibility)
  })
  onBeforeUnmount(() => {
    stop()
    document.removeEventListener('visibilitychange', onVisibility)
  })
  watch(active, sync)
}
