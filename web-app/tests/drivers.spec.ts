import { test, expect } from '@playwright/test'
import { driverMock } from './driver-helpers'
import { fixture, signedIn } from './order-helpers'

for (const status of ['FINISHED', 'PAID'] as const) {
  test(`候选司机查看 ${status} 订单不会收到本人已完成服务的提示`, async ({ page }) => {
    await driverMock(page)
    await page.route('**/api/orders/1601', route => route.fulfill({ json: { code: 0, data: fixture(status) } }))
    await page.goto('/driver/orders/1601')
    await expect(page.getByRole('heading', { name: '订单已被其他司机接走，请返回工作台查看其他订单。' })).toBeVisible()
    await expect(page.getByRole('button', { name: '开始服务' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '完成服务' })).toHaveCount(0)
  })
}

test('首次使用先保存资料；校验、失败保留输入、保存及刷新恢复', async ({ page }) => {
  const model = await driverMock(page)
  model.profile = null
  await page.goto('/driver')
  await expect(page.getByRole('button', { name: '上线接单' })).toBeDisabled()
  await page.getByRole('link', { name: '先完善司机资料' }).click()
  await page.getByRole('button', { name: '保存司机资料' }).click()
  await expect(page.getByRole('alert')).toContainText('1–64')
  await page.getByLabel('司机称呼').fill('夜航司机')
  await page.route('**/api/drivers/me/profile', route => route.abort())
  await page.getByRole('button', { name: '保存司机资料' }).click()
  await expect(page.getByRole('alert')).toContainText('无法连接')
  await expect(page.getByLabel('司机称呼')).toHaveValue('夜航司机')
  await page.unroute('**/api/drivers/me/profile')
  await page.getByRole('button', { name: '保存司机资料' }).click()
  await expect(page.getByRole('status')).toContainText('已保存')
  await page.reload()
  await expect(page.getByLabel('司机称呼')).toHaveValue('夜航司机')
  await page.getByRole('link', { name: '司机工作台' }).click()
  await expect(page.getByRole('button', { name: '上线接单' })).toBeEnabled()
})

test('上线坐标校验、30 秒心跳只使用已提交坐标；离线后不再续期', async ({ page }) => {
  const model = await driverMock(page)
  await page.clock.install()
  await page.goto('/driver')
  await page.getByLabel('司机经度').fill('')
  await page.getByRole('button', { name: '上线接单' }).click()
  await expect(page.getByRole('alert')).toContainText('有效经纬度')
  await page.getByLabel('司机经度').fill('181')
  await page.getByLabel('司机纬度').fill('86')
  await page.getByRole('button', { name: '上线接单' }).click()
  await expect(page.getByRole('alert')).toContainText('有效经纬度')
  expect(model.calls).toHaveLength(0)
  await page.getByRole('button', { name: '上海人民广场' }).click()
  await page.getByRole('button', { name: '上线接单' }).click()
  await expect(page.getByText('在线接单中', { exact: true })).toBeVisible()
  await page.getByLabel('司机经度').fill('120')
  await page.clock.fastForward(30100)
  await expect.poll(() => model.calls.filter(call => call.path.endsWith('/location')).length).toBe(1)
  expect(model.calls.at(-1)?.body).toEqual({ lng: 121.4737, lat: 31.2304 })
  await page.getByRole('button', { name: '更新位置' }).click()
  await expect.poll(() => model.calls.at(-1)?.body).toEqual({ lng: 120, lat: 31.2304 })
  await page.getByRole('button', { name: '离线休息' }).click()
  await expect(page.getByText('当前离线', { exact: true })).toBeVisible()
  const writes = model.calls.length
  await page.clock.fastForward(60100)
  expect(model.calls).toHaveLength(writes)
})

test('刷新读取服务端状态，恢复已确认坐标，在线标记过期不自动重新上线', async ({ page }) => {
  const model = await driverMock(page)
  await page.clock.install()
  await page.goto('/driver')
  await page.getByRole('button', { name: '上线接单' }).click()
  await expect(page.getByText('在线接单中', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('司机经度')).toHaveValue('117.215')
  await expect(page.getByText('在线接单中', { exact: true })).toBeVisible()
  await page.route('**/api/drivers/me/location', route => route.fulfill({ status: 409, json: { code: 'DRIVER_OFFLINE', requestId: 'expired-marker' } }))
  model.online = false
  await page.clock.fastForward(30100)
  await expect(page.getByRole('alert')).toContainText('在线状态已失效')
  await expect(page.getByRole('alert')).toContainText('expired-marker')
  await expect(page.getByRole('button', { name: '上线接单' })).toBeEnabled()
  expect(model.calls.filter(call => call.path.endsWith('/online'))).toHaveLength(1)
})

test('离开工作台停止心跳和通知轮询；返回可重新同步', async ({ page }) => {
  const model = await driverMock(page)
  let notices = 0
  page.on('request', request => { if (request.url().includes('/notices?')) notices++ })
  await page.clock.install()
  await page.goto('/driver')
  await page.getByRole('button', { name: '上线接单' }).click()
  await expect(page.getByText('在线接单中', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: '司机资料', exact: true }).click()
  const reads = notices, writes = model.calls.length
  await page.clock.fastForward(90100)
  expect(notices).toBe(reads); expect(model.calls).toHaveLength(writes)
  await page.getByRole('link', { name: '司机工作台' }).click()
  await expect.poll(() => notices).toBeGreaterThan(reads)
})

test('隐藏工作台暂停心跳与轮询，恢复页面重新读取过期状态', async ({ page }) => {
  const model = await driverMock(page)
  let notices = 0
  page.on('request', request => { if (request.url().includes('/notices?')) notices++ })
  await page.clock.install()
  await page.goto('/driver')
  await page.getByRole('button', { name: '上线接单' }).click()
  await expect(page.getByText('在线接单中', { exact: true })).toBeVisible()
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, value: true }); document.dispatchEvent(new Event('visibilitychange')) })
  const writes = model.calls.length, reads = notices
  await page.clock.fastForward(125000)
  expect(model.calls).toHaveLength(writes)
  expect(notices).toBe(reads)
  model.online = false
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, value: false }); document.dispatchEvent(new Event('visibilitychange')) })
  await expect(page.getByText('当前离线', { exact: true })).toBeVisible()
})

test('新标签页在线但无已确认坐标时不自动发送默认位置', async ({ page }) => {
  const model = await driverMock(page)
  model.online = true
  await page.clock.install()
  await page.goto('/driver')
  await expect(page.getByText('本标签页没有已确认坐标', { exact: false })).toBeVisible()
  await page.clock.fastForward(30100)
  expect(model.calls).toHaveLength(0)
  await page.getByLabel('司机经度').fill('118.5')
  await page.getByRole('button', { name: '更新位置' }).click()
  await expect.poll(() => model.calls.length).toBe(1)
  await expect(page.getByText('本标签页没有已确认坐标', { exact: false })).toHaveCount(0)
  await page.clock.fastForward(30100)
  await expect.poll(() => model.calls.length).toBe(2)
  expect(model.calls[1]!.body).toEqual({ lng: 118.5, lat: 39.136 })
})

test('过滤过时通知，局部详情失败不丢失其他待抢单', async ({ page }) => {
  const model = await driverMock(page)
  model.orders.push(fixture('CANCELLED', 1702), fixture('ACCEPTED', 1703))
  model.notices = [1701, 1702, 1703, 1704]
  await page.goto('/driver')
  await expect(page.getByRole('link', { name: /订单 #1701/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /订单 #1702|订单 #1703/ })).toHaveCount(0)
  await expect(page.getByRole('status')).toContainText('1 条通知详情')
  await page.screenshot({ path: '../logs/day17-desktop-dashboard.png', fullPage: true })
})

test('司机通过详情完成抢单、开始、完成；历史可恢复且不能模拟支付', async ({ page }) => {
  const model = await driverMock(page)
  await page.goto('/driver')
  await page.getByRole('link', { name: /订单 #1701/ }).click()
  for (const [action, status] of [['抢单', '司机已接单'], ['开始服务', '服务中'], ['完成服务', '待支付']]) {
    await page.getByRole('button', { name: action, exact: true }).click()
    await page.getByRole('button', { name: `确认${action}`, exact: true }).click()
    await expect(page.locator('.status-badge')).toHaveText(status)
  }
  expect(model.calls.filter(call => call.path.includes('/api/orders/'))).toHaveLength(3)
  await expect(page.getByRole('button', { name: '模拟支付', exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: '返回我的订单' }).click()
  await page.getByRole('link', { name: /订单 #1701/ }).click()
  await page.reload()
  await expect(page.locator('.status-badge')).toHaveText('待支付')
  await page.screenshot({ path: '../logs/day17-desktop-finished.png', fullPage: true })
})

for (const code of ['ORDER_ALREADY_TAKEN', 'DRIVER_BUSY', 'DRIVER_OFFLINE']) {
  test(`抢单 ${code} 展示清晰错误和请求编号并重新读取`, async ({ page }) => {
    const model = await driverMock(page)
    let reads = 0
    page.on('request', request => { if (request.url().endsWith('/api/orders/1701')) reads++ })
    await page.route('**/api/orders/1701/accept', async route => {
      if (code === 'ORDER_ALREADY_TAKEN') model.orders[0]!.status = 'ACCEPTED'
      await route.fulfill({ status: 409, json: { code, requestId: `day17-${code}` } })
    })
    await page.goto('/driver/orders/1701')
    await page.getByRole('button', { name: '抢单', exact: true }).click()
    await page.getByRole('button', { name: '确认抢单', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText(`day17-${code}`)
    await expect.poll(() => reads).toBeGreaterThan(1)
    if (code === 'ORDER_ALREADY_TAKEN') { await expect(page.getByRole('heading', { name: /已被其他司机接走/ })).toBeVisible(); await expect(page.getByRole('button', { name: '开始服务', exact: true })).toHaveCount(0) }
  })
}

test('提交禁用按钮，慢 GET 不覆盖接单成功状态', async ({ page }) => {
  const model = await driverMock(page)
  await page.clock.install()
  await page.goto('/driver/orders/1701')
  await expect(page.getByRole('button', { name: '抢单', exact: true })).toBeVisible()
  let releaseRead!: () => void, releaseWrite!: () => void
  await page.route('**/api/orders/1701', async route => { await new Promise<void>(resolve => { releaseRead = resolve }); await route.fulfill({ json: { code: 0, data: fixture('WAITING', 1701) } }) })
  await page.route('**/api/orders/1701/accept', async route => {
    await new Promise<void>(resolve => { releaseWrite = resolve })
    await route.fulfill({ json: { code: 0, data: { ...fixture('ACCEPTED', 1701), driverId: 17 } } })
  })
  await page.clock.fastForward(5100)
  await expect.poll(() => Boolean(releaseRead)).toBe(true)
  await page.getByRole('button', { name: '抢单', exact: true }).click()
  await page.getByRole('button', { name: '确认抢单', exact: true }).click()
  await expect(page.getByRole('button', { name: '正在处理…', exact: true })).toBeDisabled()
  releaseWrite()
  await expect(page.locator('.status-badge')).toHaveText('司机已接单')
  releaseRead()
  await expect(page.getByRole('button', { name: '开始服务', exact: true })).toBeVisible()
  expect(model.orders[0]!.status).toBe('WAITING')
})

test('状态改变时取消旧确认，防止抢单确认变成开始服务', async ({ page }) => {
  const model = await driverMock(page)
  await page.clock.install()
  await page.goto('/driver/orders/1701')
  await page.getByRole('button', { name: '抢单', exact: true }).click()
  model.orders[0]!.status = 'ACCEPTED'; model.orders[0]!.driverId = 17
  await page.clock.fastForward(5100)
  await expect(page.getByRole('button', { name: '确认开始服务', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '开始服务', exact: true })).toBeVisible()
  expect(model.calls).toHaveLength(0)
})

for (const path of ['/driver', '/driver/profile', '/driver/orders', '/driver/orders/1701']) {
  test(`乘客无法访问 ${path}`, async ({ page }) => {
    await signedIn(page)
    await page.goto(path)
    await expect(page).toHaveURL('/forbidden')
  })
}

test('状态读取失败禁止上线，保留坐标并允许刷新恢复', async ({ page }) => {
  await driverMock(page)
  await page.route('**/api/drivers/me/status', route => route.abort())
  await page.goto('/driver')
  await expect(page.getByRole('alert')).toContainText('无法连接')
  await expect(page.getByText('在线状态待确认', { exact: true })).toBeVisible()
  await page.getByLabel('司机经度').fill('118')
  await page.unroute('**/api/drivers/me/status')
  await page.getByRole('button', { name: '刷新工作台' }).click()
  await expect(page.getByRole('button', { name: '上线接单' })).toBeEnabled()
  await expect(page.getByLabel('司机经度')).toHaveValue('118')
})
