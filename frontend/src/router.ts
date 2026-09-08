import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/AuthView.vue'), meta: { public: true } },
    { path: '/', redirect: '/translate' },
    { path: '/translate', component: () => import('./views/TranslateView.vue') },
    { path: '/domains', component: () => import('./views/DomainsView.vue') },
    { path: '/learning', component: () => import('./views/DashboardView.vue') },
    { path: '/vocabulary', component: () => import('./views/VocabularyView.vue') },
    { path: '/review', component: () => import('./views/ReviewView.vue') },
    { path: '/confusions', component: () => import('./views/ConfusionsView.vue') },
    { path: '/plans', component: () => import('./views/PlansView.vue') },
    { path: '/admin', component: () => import('./views/AdminView.vue') },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.loggedIn) return '/login'
  if (to.path === '/login' && auth.loggedIn) return '/translate'
})

export default router
