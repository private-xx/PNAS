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
    { path: '/media', name: 'media', component: () => import('@/views/MediaTimelineView.vue') },
    { path: '/media/albums', name: 'media-albums', component: () => import('@/views/AlbumsView.vue') },
    { path: '/admin/jobs', name: 'jobs', component: () => import('@/views/JobsView.vue'), meta: { admin: true } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const store = useSessionStore()
  if (!store.isLoggedIn) {
    const ok = await store.refresh()
    if (!ok) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }
  if (to.meta.admin && !store.isAdmin) {
    return { name: 'media' }
  }
  return true
})

export default router
