import { createRouter, createWebHistory } from 'vue-router'
import AuthPage from './views/AuthPage.vue'
import HomePage from './views/HomePage.vue'
import StatusPage from './views/StatusPage.vue'
import { ApiError } from './api'
import { ensureAccount, homeFor, session, setExpirationHandler } from './session'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: AuthPage },
    { path: '/register', component: AuthPage },
    { path: '/passenger', component: HomePage, meta: { role: 'PASSENGER' } },
    { path: '/driver', component: HomePage, meta: { role: 'DRIVER' } },
    { path: '/forbidden', component: StatusPage, props: { kind: 'forbidden' } },
    { path: '/session-error', component: StatusPage, props: { kind: 'unavailable' } },
    { path: '/:pathMatch(.*)*', component: StatusPage, props: { kind: 'missing' } },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  // 故障页可独立访问，避免恢复会话失败导致重定向循环。
  if (to.path === '/session-error') return
  try { await ensureAccount() }
  catch (error) {
    if (!(error instanceof ApiError && error.status === 401)) return '/session-error'
  }
  if (to.meta.role && !session.account) {
    session.notice ||= '请先登录，再访问工作台。'
    return { path: '/login', query: { redirect: to.path } }
  }
  if (to.meta.role && session.account?.role !== to.meta.role) return '/forbidden'
  if ((to.path === '/login' || to.path === '/register') && session.account) return homeFor(session.account.role)
})

setExpirationHandler(() => {
  if (router.currentRoute.value.meta.role) void router.replace({ path: '/login', query: { redirect: router.currentRoute.value.path } })
})
