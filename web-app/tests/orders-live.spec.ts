import { test, expect, type APIRequestContext } from '@playwright/test'
import { appendFileSync } from 'node:fs'
import { randomInt, randomUUID } from 'node:crypto'

test.skip(process.env.SMARTDRIVE_DAY16_LIVE !== 'true', '通过 scripts/verify-day16.ps1 -Live 启用真实服务并清理临时数据')

test('真实 Gateway：浏览器下单、取消、列表，MQ 通知后司机履约，乘客轮询并模拟支付', async ({ page, request }) => {
  test.setTimeout(90000)
  const password = 'Day16!' + randomUUID()
  async function api(context: APIRequestContext, method: string, path: string, data?: unknown, token = '') {
    const response = await context.fetch(path, { method, data, headers: token ? { Authorization: `Bearer ${token}` } : {} })
    expect(response.ok(), `${method} ${path}: HTTP ${response.status()}`).toBe(true)
    const body = await response.json()
    expect(body.code).toBe(0)
    return body.data
  }
  async function account(role: string) {
    const phone = '199' + randomInt(10000000, 99999999)
    const registered = await api(request, 'POST', '/api/auth/register', { phone, password, role })
    appendFileSync('../logs/day16-created-accounts.jsonl', JSON.stringify({ userId: registered.userId, phone, role }) + '\n')
    const login = await api(request, 'POST', '/api/auth/login', { phone, password })
    return { phone, token: login.accessToken as string, userId: registered.userId }
  }
  const passenger = await account('PASSENGER')
  const driver = await account('DRIVER')
  let online = false
  try {
    await api(request, 'PUT', '/api/drivers/me/profile', { displayName: 'Day16-browser-fixture' }, driver.token)
    await api(request, 'PUT', '/api/drivers/me/online', { lng: 117.215, lat: 39.136 }, driver.token)
    online = true
    await page.goto('/login')
    await page.getByLabel('手机号', { exact: true }).fill(passenger.phone)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL('/passenger')
    const ids: number[] = []
    for (const cancelled of [true, false]) {
      await page.getByRole('link', { name: '预约代驾' }).first().click()
      await page.getByRole('button', { name: '天津站 → 南开大学' }).click()
      const created = page.waitForResponse(response => new URL(response.url()).pathname === '/api/orders' && response.request().method() === 'POST')
      await page.getByRole('button', { name: '确认下单' }).click()
      const response = await created
      expect(response.status()).toBe(201)
      const order = (await response.json()).data
      ids.push(order.id)
      await expect(page).toHaveURL(`/passenger/orders/${order.id}`)
      await expect(page.locator('.status-badge')).toHaveText('等待接单')
      if (cancelled) {
        await page.getByRole('button', { name: '取消订单', exact: true }).click()
        await page.getByRole('button', { name: '确认取消', exact: true }).click()
        await expect(page.locator('.status-badge')).toHaveText('已取消')
      } else {
        await expect.poll(async () => await api(request, 'GET', '/api/drivers/me/notices?status=NEW&limit=100', undefined, driver.token), { timeout: 20000 }).toContain(order.id)
        await api(request, 'POST', `/api/orders/${order.id}/accept`, undefined, driver.token)
        await api(request, 'POST', `/api/orders/${order.id}/start`, undefined, driver.token)
        await api(request, 'POST', `/api/orders/${order.id}/finish`, undefined, driver.token)
        // 不手动刷新，以真实 5 秒轮询获得待支付状态。
        await expect(page.getByRole('button', { name: '模拟支付', exact: true })).toBeVisible({ timeout: 10000 })
        await page.getByRole('button', { name: '模拟支付', exact: true }).click()
        await page.getByRole('button', { name: '确认模拟支付', exact: true }).click()
        await expect(page.locator('.status-badge')).toHaveText('已支付')
        await page.reload()
        await expect(page.locator('.status-badge')).toHaveText('已支付')
        await page.screenshot({ path: '../logs/day16-live-paid.png', fullPage: true })
      }
    }
    await page.getByRole('link', { name: '返回我的订单' }).click()
    for (const id of ids) await expect(page.getByText(`订单 #${id}`, { exact: true })).toBeVisible()
    await expect(page.locator('.status-badge.PAID')).toHaveCount(1)
    await expect(page.locator('.status-badge.CANCELLED')).toHaveCount(1)
    await page.screenshot({ path: '../logs/day16-live-list.png', fullPage: true })
  } finally {
    if (online) await api(request, 'PUT', '/api/drivers/me/offline', undefined, driver.token)
  }
})
