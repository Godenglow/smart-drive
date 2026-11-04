export type Role = 'PASSENGER' | 'DRIVER'
export interface Account { userId: number; phone: string; role: Role }
export interface LoginResult { accessToken: string; expiresAt: string; role: Role }

export class ApiError extends Error {
  constructor(message: string, public status = 0, public code = 'NETWORK_ERROR', public requestId = '') {
    super(message)
  }
}

const messages: Record<string, string> = {
  PHONE_ALREADY_EXISTS: '这个手机号已注册，请直接登录。',
  UNAUTHORIZED: '手机号或密码错误，请重新输入。',
  FORBIDDEN: '当前账号没有此操作的权限。',
  SERVICE_UNAVAILABLE: '服务暂时不可用，请稍后重试。',
  NO_DRIVER_NEARBY: '附近暂无在线司机，请稍后再试或调整上车地点。',
  ORDER_NOT_WAITING: '订单已不在等待状态，已为你刷新订单。',
  ORDER_STATUS_INVALID: '订单状态已变化，已为你刷新订单。',
  NOT_FOUND: '订单不存在或已被移除。',
}

let getToken = () => ''
let onUnauthorized = (_token: string) => {}
export function configureAuth(token: () => string, unauthorized: (token: string) => void) {
  getToken = token
  onUnauthorized = unauthorized
}

/** 仅接受本站 API 路径；不自动重试写请求。 */
export async function request<T>(path: string, options: { method?: string; body?: unknown; auth?: boolean; signal?: AbortSignal } = {}): Promise<T> {
  if (!path.startsWith('/api/')) throw new Error('请求地址必须以 /api/ 开头')
  const token = options.auth === false ? '' : getToken()
  const requestId = crypto.randomUUID()
  const controller = new AbortController()
  const abort = () => controller.abort()
  options.signal?.addEventListener('abort', abort, { once: true })
  if (options.signal?.aborted) controller.abort()
  const timeout = window.setTimeout(() => controller.abort(), 10_000)
  try {
    const headers: Record<string, string> = { 'X-Request-Id': requestId, Accept: 'application/json' }
    if (token) headers.Authorization = `Bearer ${token}`
    if (options.body !== undefined) headers['Content-Type'] = 'application/json'
    const response = await fetch(path, {
      method: options.method || 'GET', headers, signal: controller.signal, cache: 'no-store',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
    const payload = await response.json().catch(() => null)
    if (!response.ok || payload?.code !== 0) {
      if (response.status === 401 && token) onUnauthorized(token)
      const code = String(payload?.code || `HTTP_${response.status}`)
      const message = messages[code] || (response.status >= 500 ? '服务暂时不可用，请稍后重试。' : payload?.message) || '请求未成功，请稍后重试。'
      throw new ApiError(message, response.status, code, payload?.requestId || response.headers.get('X-Request-Id') || requestId)
    }
    return payload.data as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    throw new ApiError(controller.signal.aborted ? '请求超时，请检查网络后重试。' : '暂时无法连接服务，请检查网络后重试。', 0, 'NETWORK_ERROR', requestId)
  } finally {
    window.clearTimeout(timeout)
    options.signal?.removeEventListener('abort', abort)
  }
}

export const authApi = {
  login: (phone: string, password: string) => request<LoginResult>('/api/auth/login', { method: 'POST', body: { phone, password }, auth: false }),
  register: (phone: string, password: string, role: Role) => request<{ userId: number }>('/api/auth/register', { method: 'POST', body: { phone, password, role }, auth: false }),
  me: () => request<Account>('/api/users/me'),
}
