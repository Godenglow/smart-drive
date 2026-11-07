import { test, expect } from '@playwright/test'
import { appendFileSync } from 'node:fs'
import { randomInt, randomUUID } from 'node:crypto'

test.skip(process.env.SMARTDRIVE_STEP17_LIVE !== 'true', '通过 scripts/verify-step17.ps1 -Live 启用真实服务并清理临时数据')

test('真实 Gateway：司机浏览器保存资料、上线、心跳、MQ 待抢、抢单履约、历史及离线', async ({ page, request }) => {
  test.setTimeout(100000)
  const password = 'Step17!' + randomUUID()
  async function api(method: string, path: string, data?: unknown, token = '') {
    const response = await request.fetch(path, { method, data, headers: token ? { Authorization: `Bearer ${token}` } : {} })
    expect(response.ok(), `${method} ${path}: HTTP ${response.status()}`).toBe(true)
    const body = await response.json(); expect(body.code).toBe(0); return body.data
  }
  async function account(role: string) {
    const phone = '199' + randomInt(10000000, 99999999)
    const registered = await api('POST', '/api/auth/register', { phone, password, role })
    appendFileSync('../logs/step17-created-accounts.jsonl', JSON.stringify({ userId: registered.userId, phone, role }) + '\n')
    const login = await api('POST', '/api/auth/login', { phone, password })
    return { phone, token: login.accessToken as string, userId: registered.userId }
  }
  const passenger = await account('PASSENGER'), driver = await account('DRIVER')
  try {
    await page.goto('/login')
    await page.getByLabel('手机号', { exact: true }).fill(driver.phone)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL('/driver')
    await page.getByRole('link', { name: '先完善司机资料' }).click()
    await page.getByLabel('司机称呼').fill('Step17-browser-fixture')
    await page.getByRole('button', { name: '保存司机资料' }).click()
    await expect(page.getByRole('status')).toContainText('已保存')
    await page.getByRole('link', { name: '司机工作台' }).click()
    await page.getByRole('button', { name: '天津站', exact: true }).click()
    await page.getByRole('button', { name: '上线接单' }).click()
    await expect(page.getByText('在线接单中', { exact: true })).toBeVisible()
    await page.reload()
    await expect(page.getByText('在线接单中', { exact: true })).toBeVisible()
    const heartbeat = page.waitForResponse(response => response.url().endsWith('/api/drivers/me/location') && response.request().method() === 'PUT', { timeout: 40000 })
    expect((await heartbeat).status()).toBe(200)
    const state = await api('GET', '/api/drivers/me/status', undefined, driver.token)
    expect(state.profile.displayName).toBe('Step17-browser-fixture'); expect(state.online).toBe(true)
    const order = await api('POST', '/api/orders', { requestId: randomUUID(), pickup: { name: '天津站', lng: 117.215, lat: 39.136 }, dropoff: { name: '南开大学', lng: 117.174, lat: 39.104 } }, passenger.token)
    // 不注入通知、不拦截 API，通过真实 Outbox/RabbitMQ 匹配等待卡片出现。
    const card = page.getByRole('link', { name: new RegExp(`订单 #${order.id} `) })
    await expect(card).toBeVisible({ timeout: 25000 })
    await page.screenshot({ path: '../logs/step17-live-dashboard.png', fullPage: true })
    await card.click()
    for (const [action, status] of [['抢单', '司机已接单'], ['开始服务', '服务中'], ['完成服务', '待支付']]) {
      await page.getByRole('button', { name: action, exact: true }).click()
      await page.getByRole('button', { name: `确认${action}`, exact: true }).click()
      await expect(page.locator('.status-badge')).toHaveText(status)
    }
    await page.reload()
    await expect(page.locator('.status-badge')).toHaveText('待支付')
    await expect(page.getByRole('button', { name: '模拟支付', exact: true })).toHaveCount(0)
    await api('POST', `/api/orders/${order.id}/pay-demo`, undefined, passenger.token)
    await expect(page.locator('.status-badge')).toHaveText('已支付', { timeout: 10000 })
    await page.screenshot({ path: '../logs/step17-live-paid.png', fullPage: true })
    await page.getByRole('link', { name: '返回我的订单' }).click()
    await expect(page.getByRole('link', { name: new RegExp(`订单 #${order.id} `) })).toBeVisible()
    await page.getByRole('link', { name: '司机工作台', exact: true }).click()
    await page.getByRole('button', { name: '离线休息' }).click()
    await expect(page.getByText('当前离线', { exact: true })).toBeVisible()
    expect((await api('GET', '/api/drivers/me/status', undefined, driver.token)).online).toBe(false)
  } finally {
    // 无论页面断言是否通过，都移除本次司机的 Redis 在线标记及 GEO 成员。
    await api('PUT', '/api/drivers/me/offline', undefined, driver.token)
  }
})
