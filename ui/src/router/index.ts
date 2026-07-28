import { createRouter, createWebHistory } from 'vue-router'

import ProjectsView from '@/views/ProjectsView.vue'

/**
 * Paths are mirrored by SpaForwardController on the backend — adding a new top-level
 * prefix here means adding it there too, or a hard refresh will 404.
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'projects', component: ProjectsView },
    {
      path: '/p/:project',
      name: 'project',
      component: () => import('@/views/ProjectView.vue'),
      props: true,
    },
    {
      path: '/p/:project/graph',
      name: 'project-graph',
      component: () => import('@/views/GraphView.vue'),
      props: true,
    },
    {
      path: '/p/:project/e/:name',
      name: 'entry',
      component: () => import('@/views/EntryView.vue'),
      props: true,
    },
    {
      path: '/p/:project/t/:task',
      name: 'task',
      component: () => import('@/views/TaskView.vue'),
      props: true,
    },
    {
      path: '/p/:project/t/:task/graph',
      name: 'task-graph',
      component: () => import('@/views/GraphView.vue'),
      props: true,
    },
    {
      path: '/p/:project/t/:task/e/:name',
      name: 'task-entry',
      component: () => import('@/views/EntryView.vue'),
      props: true,
    },
    { path: '/setup', name: 'setup', component: () => import('@/views/SetupView.vue') },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
  ],
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})
