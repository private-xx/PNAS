import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      name: 'files',
      component: () => import('@/views/FilesView.vue'),
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const store = useSessionStore()
  if (store.isLoggedIn) {
    return true
  }
  const ok = await store.refresh()
  return ok ? true : { name: 'login', query: { redirect: to.fullPath } }
})

export default router
