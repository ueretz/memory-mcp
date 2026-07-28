<script setup lang="ts">
import { ref } from 'vue'

import AppHeader from './components/AppHeader.vue'
import AppSidebar from './components/AppSidebar.vue'
import SearchPalette from './components/SearchPalette.vue'

const sidebarOpen = ref(false)
const searchOpen = ref(false)
</script>

<template>
  <div class="min-h-screen bg-bg">
    <AppSidebar :open="sidebarOpen" @close="sidebarOpen = false" />

    <div class="lg:pl-64">
      <AppHeader @toggle-sidebar="sidebarOpen = !sidebarOpen" @open-search="searchOpen = true" />

      <main class="mx-auto w-full max-w-5xl px-4 pt-8 pb-24 sm:px-6 lg:px-10">
        <RouterView v-slot="{ Component, route }">
          <Transition
            mode="out-in"
            enter-active-class="transition duration-200 ease-out"
            enter-from-class="translate-y-1 opacity-0"
            leave-active-class="transition duration-100 ease-in"
            leave-to-class="opacity-0"
          >
            <component :is="Component" :key="route.fullPath" />
          </Transition>
        </RouterView>
      </main>
    </div>

    <SearchPalette v-model:open="searchOpen" />
  </div>
</template>
