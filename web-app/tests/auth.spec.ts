import { test, expect, type Page } from '@playwright/test'

const phone = '13800138000'
const password = 'Passw0rd!23'
const account = { userId: 15, phone, role: 'PASSENGER' }
async function mockApi(page: Page, role = 'PASSENGER') {
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    const data = path.endsWith('/drivers/me/status') ? { profile: null, online: false } : path.endsWith('/drivers/me/notices') ? [] : path.endsWith('/login') ? { accessToken: 'test-token', expiresAt: new Date(Date.now() + 7200_000).toISOString(), role }
      : path.endsWith('/register') ? { userId: 15 } : { ...account, role }
    await route.fulfill({ json: { code: 0, message: 'ok', data } })
  })
}
async function login(page: Page) {
  await page.goto('/login')
  await page.getByLabel('手机号', { exact: true }).fill(phone)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
}

test('未登录访问受保护页面，提示登录并保留目标', async ({ page }) => {
  await page.goto('/driver')
  await expect(page).toHaveURL(url => url.pathname === '/login' && url.searchParams.get('redirect') === '/driver')
  await expect(page.getByRole('status')).toContainText('请先登录')
  await page.screenshot({ path: '../logs/day15-desktop-login.png', fullPage: true })
})

for (const [role, home, title] of [['PASSENGER', '/passenger', '乘客身份已就绪'], ['DRIVER', '/driver', '司机身份已就绪']]) {
  test(`${role} 登录、刷新恢复、退出、后退均受保护`, async ({ page }) => {
    await mockApi(page, role)
    await login(page)
    await expect(page).toHaveURL(home)
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    expect(await page.evaluate(() => sessionStorage.getItem('smartdrive.session'))).not.toContain(password)
    await page.reload()
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    // 先产生受保护页面的历史项；登录/退出本身均 replace，不会新增历史项。
    await page.goto(home + '?history=1')
    await expect(page.getByRole('heading', { name: title })).toBeVisible()
    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page.getByRole('status')).toContainText('安全退出')
    await page.goBack()
    await expect(page).toHaveURL(/\/login/)
    expect(await page.evaluate(() => sessionStorage.getItem('smartdrive.session'))).toBeNull()
  })
}

for (const [role, wrong] of [['PASSENGER', '/driver'], ['DRIVER', '/passenger']]) {
  test(`${role} 不能进入另一角色首页`, async ({ page }) => {
    await mockApi(page, role)
    await login(page)
    await expect(page).toHaveURL(role === 'DRIVER' ? '/driver' : '/passenger')
    await page.goto(wrong)
    await expect(page.getByRole('heading', { name: '当前身份无法访问此页面' })).toBeVisible()
    await page.getByRole('link', { name: '返回首页' }).click()
    await expect(page).toHaveURL(role === 'DRIVER' ? '/driver' : '/passenger')
  })
}

test('注册校验、司机角色、重复点击和成功跳转', async ({ page }) => {
  await mockApi(page)
  const bodies: unknown[] = []
  await page.route('**/api/auth/register', async route => {
    bodies.push(route.request().postDataJSON())
    await new Promise(resolve => setTimeout(resolve, 300))
    await route.fulfill({ json: { code: 0, data: { userId: 16 } } })
  })
  await page.goto('/register')
  await page.screenshot({ path: '../logs/day15-desktop-register.png', fullPage: true })
  await page.getByRole('button', { name: '创建账号' }).click()
  await expect(page.getByRole('alert')).toContainText('11 位手机号')
  await page.getByLabel('手机号', { exact: true }).fill(phone)
  await page.getByLabel('密码', { exact: true }).fill('123')
  await page.getByRole('button', { name: '创建账号' }).click()
  await expect(page.getByRole('alert')).toContainText('8–64')
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByLabel('确认密码').fill('different')
  await page.getByRole('button', { name: '创建账号' }).click()
  await expect(page.getByRole('alert')).toContainText('不一致')
  await page.getByLabel('确认密码').fill(password)
  await page.getByRole('radio', { name: /司机/ }).check()
  await page.getByRole('button', { name: '创建账号' }).click()
  await expect(page.getByRole('button', { name: '正在处理' })).toBeDisabled()
  await expect(page).toHaveURL(/\/login\?phone=.*registered=1/)
  await expect(page.getByRole('status')).toContainText('注册成功')
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('')
  expect(bodies).toEqual([{ phone, password, role: 'DRIVER' }])
})

test('错误密码与重复手机号展示业务错误和 requestId', async ({ page }) => {
  await page.route('**/api/auth/**', route => route.fulfill({ status: route.request().url().endsWith('/login') ? 401 : 409, json: { code: route.request().url().endsWith('/login') ? 'UNAUTHORIZED' : 'PHONE_ALREADY_EXISTS', requestId: 'day15-error-trace' } }))
  await login(page)
  await expect(page.getByRole('alert')).toContainText('手机号或密码错误')
  await expect(page.getByRole('alert')).toContainText('day15-error-trace')
  await page.goto('/register')
  await page.getByLabel('手机号', { exact: true }).fill(phone)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByLabel('确认密码').fill(password)
  await page.getByRole('button', { name: '创建账号' }).click()
  await expect(page.getByRole('alert')).toContainText('已注册')
})

test('网络失败保留表单，可重试登录；密码可切换显示', async ({ page }) => {
  await page.route('**/api/auth/login', route => route.abort())
  await login(page)
  await expect(page.getByRole('alert')).toContainText('无法连接')
  await expect(page.getByLabel('手机号', { exact: true })).toHaveValue(phone)
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue(password)
  await page.getByRole('button', { name: '显示密码' }).click()
  await expect(page.getByLabel('密码', { exact: true })).toHaveAttribute('type', 'text')
  await mockApi(page)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/passenger')
})

test('超时中止请求并允许重试', async ({ page }) => {
  await page.route('**/api/auth/login', () => {})
  await page.clock.install()
  await login(page)
  await page.clock.fastForward(10_100)
  await expect(page.getByRole('alert')).toContainText('请求超时')
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled()
})

test('非 JSON 服务错误有可读提示，不泄露响应正文', async ({ page }) => {
  await page.route('**/api/auth/login', route => route.fulfill({ status: 502, contentType: 'text/html', body: '<h1>internal proxy details</h1>' }))
  await login(page)
  await expect(page.getByRole('alert')).toContainText('服务暂时不可用')
  await expect(page.getByRole('alert')).not.toContainText('internal proxy')
})

test('过期与损坏缓存安全回到登录页', async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('smartdrive.session', JSON.stringify({ token: 'expired', expiresAt: Date.now() - 1000 })))
  await page.goto('/passenger')
  await expect(page.getByRole('status')).toContainText('已失效')
  await page.addInitScript(() => sessionStorage.setItem('smartdrive.session', '{broken'))
  await page.goto('/passenger')
  await expect(page).toHaveURL(/\/login/)
  expect(await page.evaluate(() => sessionStorage.getItem('smartdrive.session'))).toBeNull()
})

test('服务端拒绝已保存的令牌，清空会话并提示过期', async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('smartdrive.session', JSON.stringify({ token: 'forged', expiresAt: Date.now() + 7200_000 })))
  await page.route('**/api/users/me', route => route.fulfill({ status: 401, json: { code: 'UNAUTHORIZED' } }))
  await page.goto('/passenger')
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('status')).toContainText('已失效')
  expect(await page.evaluate(() => sessionStorage.getItem('smartdrive.session'))).toBeNull()
})

test('恢复失败保留会话，可重新连接；身份使用服务端角色', async ({ page }) => {
  await page.goto('/login')
  await page.evaluate(() => sessionStorage.setItem('smartdrive.session', JSON.stringify({ token: 'test-token', role: 'DRIVER', expiresAt: Date.now() + 7200_000 })))
  await page.route('**/api/users/me', route => route.fulfill({ status: 503, json: { code: 'SERVICE_UNAVAILABLE' } }))
  await page.goto('/passenger')
  await expect(page).toHaveURL('/session-error')
  expect(await page.evaluate(() => sessionStorage.getItem('smartdrive.session'))).toContain('test-token')
  await mockApi(page)
  await page.getByRole('link', { name: '重新连接' }).click()
  await expect(page).toHaveURL('/passenger')
})

test('停留在首页期间到期，立即返回登录', async ({ page }) => {
  await mockApi(page)
  await page.clock.install()
  await login(page)
  await expect(page).toHaveURL('/passenger')
  await page.clock.fastForward(7200_100)
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('status')).toContainText('已失效')
})

test('请求带 Bearer 和追踪头，公开登录请求不带 Bearer', async ({ page }) => {
  await mockApi(page)
  const loginRequest = page.waitForRequest('**/api/auth/login')
  const meRequest = page.waitForRequest('**/api/users/me')
  await login(page)
  expect((await loginRequest).headers().authorization).toBeUndefined()
  const headers = (await meRequest).headers()
  expect(headers.authorization).toBe('Bearer test-token')
  expect(headers['x-request-id']).toMatch(/^[\da-f-]{36}$/)
})

test('不接受外部登录回跳地址；未知页面有返回入口', async ({ page }) => {
  await mockApi(page)
  await page.goto('/login?redirect=https://example.com')
  await page.getByLabel('手机号', { exact: true }).fill(phone)
  await page.getByLabel('密码', { exact: true }).fill(password)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL('/passenger')
  await page.goto('/not-found')
  await expect(page.getByRole('heading', { name: '这条路暂时没有抵达点' })).toBeVisible()
})
