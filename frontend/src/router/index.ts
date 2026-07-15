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
  ],
})

export default router
