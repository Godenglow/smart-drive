import { test, expect, type Page, type BrowserContext } from '@playwright/test'
import { appendFileSync, mkdirSync, writeFileSync, statSync } from 'node:fs'
import { randomInt, randomUUID } from 'node:crypto'

test.skip(process.env.SMARTDRIVE_STEP18_LIVE !== 'true', '通过 scripts/verify-step18.ps1 -Live 启用真实联调、录制及临时数据清理')

test('独立乘客与司机会话：取消、MQ 待抢、双司机竞争、刷新履约及模拟支付录制', async ({ browser, request }, testInfo) => {
  test.setTimeout(180000)
  mkdirSync('../logs/step18-video', { recursive: true })
  const password = 'Step18!' + randomUUID()
  const accounts: { userId: number; phone: string; role: string; token: string }[] = []
  const sessions: { name: string; context: BrowserContext; page: Page }[] = []
  const timeline: { step: string; at: string; [key: string]: unknown }[] = []
  const writes: { actor: string; path: string }[] = []
  const note = (step: string, detail = {}) => timeline.push({ step, at: new Date().toISOString(), ...detail })
  async function api(method: string, path: string, data?: unknown, token = '') {
    const response = await request.fetch(path, { method, data, headers: token ? { Authorization: `Bearer ${token}` } : {} })
    expect(response.ok(), `${method} ${path}: HTTP ${response.status()}`).toBe(true)
    const body = await response.json(); expect(body.code).toBe(0); return body.data
  }
  async function account(role: string) {
    const phone = '199' + randomInt(10000000, 99999999)
    const registered = await api('POST', '/api/auth/register', { phone, password, role })
    appendFileSync('../logs/step18-created-accounts.jsonl', JSON.stringify({ userId: registered.userId, phone, role }) + '\n')
    const login = await api('POST', '/api/auth/login', { phone, password })
    const result = { userId: registered.userId as number, phone, role, token: login.accessToken as string }
    accounts.push(result)
    return result
  }
  async function session(name: string, actor: Awaited<ReturnType<typeof account>>) {
    const context = await browser.newContext({ baseURL: 'http://127.0.0.1:5173', viewport: { width: 1440, height: 1000 }, recordVideo: { dir: '../logs/step18-video', size: { width: 1440, height: 1000 } } })
    const page = await context.newPage()
    sessions.push({ name, context, page })
    page.on('request', req => {
      if (req.method() === 'POST' && new URL(req.url()).pathname.startsWith('/api/orders')) writes.push({ actor: name, path: new URL(req.url()).pathname })
    })
    await page.goto('/login')
    await page.getByLabel('手机号', { exact: true }).fill(actor.phone)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page).toHaveURL(actor.role === 'DRIVER' ? '/driver' : '/passenger')
    expect((await api('GET', '/api/users/me', undefined, actor.token)).userId).toBe(actor.userId)
    return page
  }
  // 同一事件循环连续点击，验证 busy 守卫；不拦截、延迟或替换真实 API。
  async function twice(page: Page, label: string) {
    await page.getByRole('button', { name: label, exact: true }).evaluate((button: HTMLButtonElement) => { button.click(); button.click() })
  }
  async function action(page: Page, label: string, status: string) {
    await page.getByRole('button', { name: label, exact: true }).click()
    await twice(page, label === '取消订单' ? '确认取消' : `确认${label}`)
    await expect(page.locator('.status-badge')).toHaveText(status)
  }
  async function create(page: Page) {
    await page.goto('/passenger/orders/new')
    await page.getByRole('button', { name: '天津站 → 南开大学' }).click()
    const created = page.waitForResponse(res => new URL(res.url()).pathname === '/api/orders' && res.request().method() === 'POST')
    await twice(page, '确认下单')
    const response = await created; expect(response.status()).toBe(201)
    const order = (await response.json()).data
    await expect(page).toHaveURL(`/passenger/orders/${order.id}`)
    await expect(page.locator('.status-badge')).toHaveText('等待接单')
    return order.id as number
  }
  try {
    const passenger = await account('PASSENGER'), a = await account('DRIVER'), b = await account('DRIVER')
    const p = await session('passenger', passenger), da = await session('driver-a', a), db = await session('driver-b', b)
    expect(new Set(sessions.map(item => item.context)).size).toBe(3)
    for (const d of [da, db]) {
      await d.getByRole('link', { name: '先完善司机资料' }).click()
      await d.getByLabel('司机称呼').fill('Step18-browser-fixture')
      await d.getByRole('button', { name: '保存司机资料' }).click()
      await expect(d.getByRole('status')).toContainText('已保存')
      await d.getByRole('link', { name: '司机工作台' }).click()
      await d.getByRole('button', { name: '天津站', exact: true }).click()
      await d.getByRole('button', { name: '上线接单' }).click()
      await expect(d.getByText('在线接单中', { exact: true })).toBeVisible()
      await d.reload()
      await expect(d.getByText('在线接单中', { exact: true })).toBeVisible()
    }
    note('三个独立会话登录，两位司机通过页面保存资料、上线并刷新恢复')
    const cancelledId = await create(p)
    await action(p, '取消订单', '已取消')
    await p.reload(); await expect(p.locator('.status-badge')).toHaveText('已取消')
    note('乘客下单与取消，连续点击只产生一次写请求', { cancelledId })
    const orderId = await create(p)
    for (const d of [da, db]) {
      const card = d.getByRole('link', { name: new RegExp(`订单 #${orderId} `) })
      await expect(card).toBeVisible({ timeout: 25000 })
      await expect(d.getByRole('link', { name: new RegExp(`订单 #${cancelledId} `) })).toHaveCount(0)
      await card.click()
      await expect(d.locator('.status-badge')).toHaveText('等待接单')
    }
    note('真实 Outbox/RabbitMQ 向两位候选司机分发待抢通知', { orderId })
    // 两位司机同时提交；服务端决定赢家，测试不预设归属。
    await Promise.all([da, db].map(d => d.getByRole('button', { name: '抢单', exact: true }).click()))
    const responses = [da, db].map(d => d.waitForResponse(res => res.url().endsWith(`/api/orders/${orderId}/accept`) && res.request().method() === 'POST'))
    await Promise.all([da, db].map(d => twice(d, '确认抢单')))
    const accepted = await Promise.all(responses)
    expect(accepted.map(res => res.status()).sort()).toEqual([200, 409])
    const winnerIndex = accepted[0]!.status() === 200 ? 0 : 1
    const winner = [da, db][winnerIndex]!, loser = [da, db][1 - winnerIndex]!
    const winnerAccount = [a, b][winnerIndex]!, loserAccount = [a, b][1 - winnerIndex]!
    expect((await accepted[1 - winnerIndex]!.json()).code).toBe('ORDER_ALREADY_TAKEN')
    await expect(loser.getByRole('alert')).toContainText('订单已被其他司机接走')
    await expect(loser.getByRole('alert')).toContainText('请求编号')
    await expect(loser.getByRole('heading', { name: /已被其他司机接走/ })).toBeVisible()
    await expect(loser.getByRole('button', { name: /抢单|开始服务|完成服务/ })).toHaveCount(0)
    await loser.screenshot({ path: '../logs/step18-live-conflict.png', fullPage: true })
    await loser.reload()
    await expect(loser.getByRole('heading', { name: /已被其他司机接走/ })).toBeVisible()
    const candidate = await api('GET', `/api/orders/${orderId}`, undefined, loserAccount.token)
    expect([candidate.passengerId, candidate.requestId, candidate.driverId]).toEqual([null, null, null])
    note('竞争恰好一人成功，失败司机得到真实 409 与请求编号，刷新后不可履约', { winner: winnerAccount.userId, loser: loserAccount.userId, statuses: accepted.map(res => res.status()) })
    for (const [label, status] of [['接单', '司机已接单'], ['开始服务', '服务中'], ['完成服务', '待支付']] as const) {
      if (label !== '接单') await action(winner, label, status)
      await expect(p.locator('.status-badge')).toHaveText(status, { timeout: 12000 })
      for (const page of [winner, p]) { await page.reload(); await expect(page.locator('.status-badge')).toHaveText(status) }
      note(`${label}后乘客自动同步，双方刷新恢复 ${status}`)
    }
    await expect(winner.getByRole('button', { name: '模拟支付', exact: true })).toHaveCount(0)
    await action(p, '模拟支付', '已支付')
    await expect(winner.locator('.status-badge')).toHaveText('已支付', { timeout: 12000 })
    for (const [name, page] of [['passenger', p], ['driver', winner]] as const) {
      await page.reload(); await expect(page.locator('.status-badge')).toHaveText('已支付')
      await page.screenshot({ path: `../logs/step18-live-${name}-paid.png`, fullPage: true })
      await page.getByRole('link', { name: '返回我的订单' }).click()
      await expect(page.getByRole('link', { name: new RegExp(`订单 #${orderId} `) })).toBeVisible()
      await expect(page.locator('.status-badge.PAID')).toHaveCount(1)
    }
    await expect(p.locator('.status-badge.CANCELLED')).toHaveCount(1)
    await loser.goto('/driver/orders')
    await expect(loser.getByRole('heading', { name: '还没有这一程的记录' })).toBeVisible()
    const final = await api('GET', `/api/orders/${orderId}`, undefined, passenger.token)
    expect(final.status).toBe('PAID'); expect(final.driverId).toBe(winnerAccount.userId)
    for (const d of [da, db]) {
      await d.getByRole('link', { name: '司机工作台', exact: true }).click()
      await d.getByRole('button', { name: '离线休息' }).click()
      await expect(d.getByText('当前离线', { exact: true })).toBeVisible()
    }
    expect(writes.filter(item => item.path === '/api/orders')).toHaveLength(2)
    for (const suffix of ['accept', 'start', 'finish', 'pay-demo']) {
      expect(writes.filter(item => item.path === `/api/orders/${orderId}/${suffix}`)).toHaveLength(suffix === 'accept' ? 2 : 1)
    }
    expect(writes.filter(item => item.path === `/api/orders/${cancelledId}/cancel`)).toHaveLength(1)
    note('模拟支付、双方历史、失败司机历史隔离、司机离线及全部写请求次数核对通过', { finalStatus: final.status, writes })
  } finally {
    try {
      for (const actor of accounts.filter(item => item.role === 'DRIVER')) await api('PUT', '/api/drivers/me/offline', undefined, actor.token)
    } finally {
      const videoErrors: unknown[] = []
      for (const { name, context, page } of sessions) {
        try {
          const video = page.video()
          await context.close()
          expect(video).not.toBeNull()
          const path = `../logs/step18-${name}.webm`
          await video!.saveAs(path)
          expect(statSync(path).size).toBeGreaterThan(10000)
          await testInfo.attach(name, { path, contentType: 'video/webm' })
        } catch (error) { videoErrors.push(error) }
      }
      writeFileSync('../logs/step18-timeline.json', JSON.stringify({ timeline, writes, videoErrors: videoErrors.map(String) }, null, 2))
      expect(videoErrors).toHaveLength(0)
    }
  }
})
