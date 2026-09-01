import { createRouter, createWebHistory } from 'vue-router'

import ProjectsView from '@/views/ProjectsView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    /** Full-bleed page: App.vue skips the sidebar/header/content column for these. */
    bare?: boolean
  }
}

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
      path: '/p/:project/pipelines',
      name: 'pipelines',
      component: () => import('@/views/PipelinesView.vue'),
      props: true,
    },
    {
      path: '/p/:project/pipelines/new',
      name: 'pipeline-new',
      component: () => import('@/views/PipelineBuilderView.vue'),
      props: true,
    },
    {
      path: '/p/:project/pipelines/:slug/edit',
      name: 'pipeline-edit',
      component: () => import('@/views/PipelineBuilderView.vue'),
      props: true,
    },
    {
      path: '/p/:project/pipelines/:slug/board',
      name: 'pipeline-board',
      component: () => import('@/views/PipelineBoardView.vue'),
      props: true,
      meta: { bare: true },
    },
    {
      path: '/p/:project/pipelines/:slug',
      name: 'pipeline',
      component: () => import('@/views/PipelineView.vue'),
      props: true,
    },
    {
      path: '/p/:project/pipelines/:slug/runs/:runId',
      name: 'pipeline-run',
      component: () => import('@/views/PipelineRunView.vue'),
      props: true,
    },
    {
      path: '/p/:project/f/:folder',
      name: 'folder',
      component: () => import('@/views/FolderView.vue'),
      props: true,
    },
    {
      path: '/p/:project/e/:name',
      name: 'entry',
      component: () => import('@/views/EntryView.vue'),
      props: true,
    },
    {
      path: '/p/:project/e/:name/report',
      name: 'entry-report',
      component: () => import('@/views/ReportView.vue'),
      props: true,
      meta: { bare: true },
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
    {
      path: '/p/:project/t/:task/e/:name/report',
      name: 'task-entry-report',
      component: () => import('@/views/ReportView.vue'),
      props: true,
      meta: { bare: true },
    },
    { path: '/setup', name: 'setup', component: () => import('@/views/SetupView.vue') },
    { path: '/settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
    { path: '/stats', name: 'stats', component: () => import('@/views/StatsView.vue') },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
  ],
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})
