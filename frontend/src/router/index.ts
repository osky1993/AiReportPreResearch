import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView,
    },
    {
      path: '/ask',
      name: 'ask',
      component: () => import('../views/AskView.vue'),
    },
    {
      path: '/report',
      name: 'report',
      component: () => import('../views/ReportListView.vue'),
    },
    {
      path: '/report/runs/:id',
      name: 'report-run',
      component: () => import('../views/ReportRunView.vue'),
    },
    {
      path: '/assets',
      redirect: '/assets/templates',
    },
    {
      path: '/assets/templates/new',
      name: 'template-author',
      component: () => import('../views/TemplateAuthorView.vue'),
    },
    {
      path: '/assets/metrics/new',
      name: 'metric-wizard',
      component: () => import('../views/MetricWizardView.vue'),
    },
    {
      path: '/assets/:kind',
      name: 'assets',
      component: () => import('../views/AssetsView.vue'),
    },
    {
      path: '/assets/:kind/:id',
      name: 'asset-detail',
      component: () => import('../views/AssetDetailView.vue'),
    },
  ],
})

export default router
