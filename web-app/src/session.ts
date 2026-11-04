import { reactive } from 'vue'
import { ApiError, authApi, configureAuth, type Account, type LoginResult, type Role } from './api'

const KEY = 'smartdrive.session'
export const session = reactive({ token: '', expiresAt: 0, account: null as Account | null, notice: '' })
let expiryTimer: number | undefined
let restoring: Promise<void> | null = null
let onExpired = () => {}
export function setExpirationHandler(handler: () => void) { onExpired = handler }
export function homeFor(role?: Role) { return role === 'DRIVER' ? '/driver' : '/passenger' }

function persist() {
  try {
    if (session.token) sessionStorage.setItem(KEY, JSON.stringify({ token: session.token, expiresAt: session.expiresAt }))
    else sessionStorage.removeItem(KEY)
  } catch { /* 禁止存储的浏览器仍可在本页使用内存会话。 */ }
}

export function clearSession(notice = '') {
  window.clearTimeout(expiryTimer)
  session.token = ''
  session.account = null
  session.expiresAt = 0
  session.notice = notice
  persist()
}
function expire() {
  clearSession('登录已失效，请重新登录。')
  onExpired()
}
function scheduleExpiry() {
  window.clearTimeout(expiryTimer)
  expiryTimer = window.setTimeout(expire, Math.min(Math.max(0, session.expiresAt - Date.now()), 2_147_483_647))
}
try {
  const saved = JSON.parse(sessionStorage.getItem(KEY) || 'null')
  if (saved && typeof saved.token === 'string' && saved.token && Number.isFinite(saved.expiresAt) && saved.expiresAt > Date.now()) {
    session.token = saved.token
    session.expiresAt = saved.expiresAt
    scheduleExpiry()
  } else if (saved) clearSession('登录已失效，请重新登录。')
} catch { clearSession() }

configureAuth(() => session.token, (token) => { if (session.token === token) expire() })

export async function ensureAccount() {
  if (!session.token) return
  if (session.expiresAt <= Date.now()) { expire(); return }
  if (session.account) return
  if (restoring) return restoring
  const token = session.token
  restoring = (async () => {
    const account = await authApi.me()
    if (account.role !== 'PASSENGER' && account.role !== 'DRIVER') {
      clearSession()
      throw new ApiError('账户角色无效，请重新登录。', 401)
    }
    if (session.token === token) session.account = account
  })().finally(() => { restoring = null })
  return restoring
}

export async function signIn(phone: string, password: string) {
  const result: LoginResult = await authApi.login(phone, password)
  const expiresAt = Date.parse(result.expiresAt)
  if (!result.accessToken || !Number.isFinite(expiresAt) || expiresAt <= Date.now()) throw new ApiError('登录响应无效，请重试。')
  clearSession()
  session.token = result.accessToken
  session.expiresAt = expiresAt
  try {
    await ensureAccount()
    if (!session.account) throw new ApiError('登录已失效，请重试。', 401)
    persist()
    scheduleExpiry()
  } catch (error) { clearSession(); throw error }
}
