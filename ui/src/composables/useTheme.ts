import { ref } from 'vue'

const STORAGE_KEY = 'memory-mcp:theme'

function prefersDark(): boolean {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

function initial(): boolean {
  const stored = localStorage.getItem(STORAGE_KEY)
  return stored === 'dark' || (stored !== 'light' && prefersDark())
}

// Shared across every component that asks for it.
const isDark = ref(initial())

export function useTheme() {
  function apply(dark: boolean) {
    isDark.value = dark
    document.documentElement.classList.toggle('dark', dark)
    localStorage.setItem(STORAGE_KEY, dark ? 'dark' : 'light')
  }

  return {
    isDark,
    toggle: () => apply(!isDark.value),
  }
}
