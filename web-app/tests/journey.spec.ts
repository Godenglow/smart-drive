import { test, expect } from '@playwright/test'
import { driverMock } from './driver-helpers'
import { fixture } from './order-helpers'

test('抢单冲突立即取代旧轮询，显示最新归属并取消旧确认', async ({ page }) => {
  const model = await driverMock(page)
  await page.clock.install()
  await page.goto('/driver/orders/1701')
  await expect(page.getByRole('button', { name: '抢单', exact: true })).toBeVisible()
  let reads = 0, release!: () => void
  const pending = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/orders/1701', async route => {
    if (++reads === 1) await pending
    await route.fulfill({ json: { code: 0, data: model.orders[0] } }).catch(() => {})
  })
  await page.route('**/api/orders/1701/accept', route => {
    model.orders[0]!.status = 'ACCEPTED'
    return route.fulfill({ status: 409, json: { code: 'ORDER_ALREADY_TAKEN', requestId: 'step18-conflict' } })
  })
  try {
    await page.clock.fastForward(5100)
    await expect.poll(() => reads).toBe(1)
    await page.getByRole('button', { name: '抢单', exact: true }).click()
    await page.getByRole('button', { name: '确认抢单', exact: true }).click()
    // 不推进下一次轮询的时钟：冲突后必须立刻发起第二次读取。
    await expect(page.getByRole('alert')).toContainText('step18-conflict')
    await expect(page.getByRole('heading', { name: /已被其他司机接走/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /确认抢单|开始服务/ })).toHaveCount(0)
    expect(reads).toBe(2)
  } finally { release() }
})

test('写入结果未知且同步失败时禁止重复操作，手动刷新恢复服务端状态', async ({ page }) => {
  const model = await driverMock(page)
  await page.goto('/driver/orders/1701')
  await expect(page.getByRole('button', { name: '抢单', exact: true })).toBeVisible()
  let writes = 0
  await page.route('**/api/orders/1701', route => route.abort())
  await page.route('**/api/orders/1701/accept', route => {
    writes++; model.orders[0]!.status = 'ACCEPTED'; model.orders[0]!.driverId = 17
    return route.abort() // 模拟服务端已提交，但响应丢失。
  })
  await page.getByRole('button', { name: '抢单', exact: true }).click()
  await page.getByRole('button', { name: '确认抢单', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('操作结果待确认')
  await expect(page.getByRole('alert').first()).toContainText('无法连接')
  await expect(page.getByRole('button', { name: '抢单', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: '刷新状态', exact: true })).toBeEnabled()
  await page.unroute('**/api/orders/1701')
  await page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(page.locator('.status-badge')).toHaveText('司机已接单')
  await expect(page.getByRole('button', { name: '开始服务', exact: true })).toBeEnabled()
  await expect(page.getByText('操作结果待确认', { exact: false })).toHaveCount(0)
  await page.reload()
  await expect(page.getByRole('button', { name: '开始服务', exact: true })).toBeEnabled()
  expect(writes).toBe(1)
})

test('旧轮询的网络错误不能禁用刚成功后的下一步操作', async ({ page }) => {
  await driverMock(page)
  await page.clock.install()
  await page.goto('/driver/orders/1701')
  await expect(page.getByRole('button', { name: '抢单', exact: true })).toBeVisible()
  let release!: () => void, reading = false
  const pending = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/orders/1701', async route => { reading = true; await pending; await route.abort() })
  try {
    await page.clock.fastForward(5100)
    await expect.poll(() => reading).toBe(true)
    await page.getByRole('button', { name: '抢单', exact: true }).click()
    await page.getByRole('button', { name: '确认抢单', exact: true }).click()
    await expect(page.locator('.status-badge')).toHaveText('司机已接单')
    release()
    await expect(page.getByRole('button', { name: '刷新状态', exact: true })).toBeEnabled()
    await expect(page.getByRole('alert')).toHaveCount(0)
    await expect(page.getByRole('button', { name: '开始服务', exact: true })).toBeEnabled()
  } finally { release() }
})

for (const [initial, action, status] of [
  ['WAITING', '抢单', '司机已接单'], ['ACCEPTED', '开始服务', '服务中'], ['IN_SERVICE', '完成服务', '待支付'],
] as const) {
  test(`${action} 连续触发两次确认只写入一次，刷新以服务端为准`, async ({ page }) => {
    const model = await driverMock(page)
    model.orders[0] = { ...fixture(initial, 1701), driverId: initial === 'WAITING' ? null : 17 }
    await page.goto('/driver/orders/1701')
    await page.getByRole('button', { name: action, exact: true }).click()
    await page.getByRole('button', { name: `确认${action}`, exact: true }).evaluate((button: HTMLButtonElement) => { button.click(); button.click() })
    await expect(page.locator('.status-badge')).toHaveText(status)
    expect(model.calls).toHaveLength(1)
    await page.reload()
    await expect(page.locator('.status-badge')).toHaveText(status)
    await expect(page.getByRole('button', { name: `确认${action}`, exact: true })).toHaveCount(0)
  })
}
