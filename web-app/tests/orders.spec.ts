import { test, expect } from '@playwright/test'
import { fixture, signedIn, preset } from './order-helpers'
import type { CreateOrder, OrderStatus } from '../src/orders'

test.beforeEach(async ({ page }) => { await signedIn(page) })

test('首页入口、预设与空值/名称/经纬度校验，不发送非法请求', async ({ page }) => {
  let creates = 0
  await page.route('**/api/orders', route => { creates++; return route.abort() })
  await page.goto('/passenger')
  await page.getByRole('link', { name: '预约代驾' }).first().click()
  await page.getByRole('button', { name: '确认下单' }).click()
  await expect(page.getByRole('alert')).toContainText('上车地点名称')
  await page.getByRole('button', { name: '人民广场 → 静安寺' }).click()
  await expect(page.getByLabel('目的地点名称')).toHaveValue('静安寺')
  await page.getByLabel('上车经度').fill('181')
  await page.getByRole('button', { name: '确认下单' }).click()
  await expect(page.getByRole('alert')).toContainText('经度')
  await page.getByLabel('上车经度').fill('')
  await page.getByRole('button', { name: '确认下单' }).click()
  await expect(page.getByRole('alert')).toContainText('经度')
  await page.getByLabel('上车经度').fill('117.215')
  await page.getByLabel('目的地纬度').fill('86')
  await page.getByRole('button', { name: '确认下单' }).click()
  await expect(page.getByRole('alert')).toContainText('纬度')
  expect(creates).toBe(0)
})

test('创建中禁用重复点击，成功进入详情并清除草稿', async ({ page }) => {
  const bodies: CreateOrder[] = []
  await page.route('**/api/orders', async route => {
    bodies.push(route.request().postDataJSON())
    expect(route.request().headers().authorization).toBe('Bearer order-test')
    await new Promise(resolve => setTimeout(resolve, 300))
    await route.fulfill({ status: 201, json: { code: 0, data: fixture() } })
  })
  await page.route('**/api/orders/1601', route => route.fulfill({ json: { code: 0, data: fixture() } }))
  await preset(page)
  await page.getByRole('button', { name: '确认下单' }).click()
  await expect(page.getByRole('button', { name: '正在提交' })).toBeDisabled()
  await expect(page.getByLabel('上车地点名称')).toBeDisabled()
  await expect(page).toHaveURL('/passenger/orders/1601')
  await expect(page.locator('.status-badge.WAITING')).toBeVisible()
  expect(bodies).toHaveLength(1)
  expect(bodies[0]?.requestId).toMatch(/^[\da-f-]{36}$/)
  expect(await page.evaluate(() => sessionStorage.getItem('smartdrive.order-draft.16'))).toBeNull()
  await page.screenshot({ path: '../logs/day16-desktop-detail.png', fullPage: true })
})

test('网络失败及刷新保留内容和幂等键，再次确认复用原请求', async ({ page }) => {
  const bodies: CreateOrder[] = []
  await page.route('**/api/orders', route => { bodies.push(route.request().postDataJSON()); return route.abort() })
  await preset(page)
  await page.getByRole('button', { name: '确认下单' }).click()
  await expect(page.getByRole('alert')).toContainText('无法连接')
  await page.reload()
  await expect(page.getByLabel('上车地点名称')).toHaveValue('天津站')
  await expect(page.getByLabel('上车地点名称')).toBeDisabled()
  await page.getByRole('button', { name: '重试确认原订单' }).click()
  await expect.poll(() => bodies.length).toBe(2)
  expect(bodies[1]).toEqual(bodies[0])
})

test('明确无司机错误保留并解锁表单，修改坐标后可重试', async ({ page }) => {
  await page.route('**/api/orders', route => route.fulfill({ status: 409, json: { code: 'NO_DRIVER_NEARBY', requestId: 'day16-no-driver' } }))
  await preset(page)
  await page.getByRole('button', { name: '确认下单' }).click()
  await expect(page.getByRole('alert')).toContainText('附近暂无在线司机')
  await expect(page.getByRole('alert')).toContainText('day16-no-driver')
  await expect(page.getByLabel('上车地点名称')).toBeEnabled()
  await expect(page.getByLabel('上车地点名称')).toHaveValue('天津站')
  await page.screenshot({ path: '../logs/day16-desktop-create.png', fullPage: true })
})

test('超时与非 JSON 502 均保留原单待确认', async ({ page }) => {
  await page.route('**/api/orders', () => {})
  await page.clock.install()
  await preset(page)
  await page.getByRole('button', { name: '确认下单' }).click()
  await page.clock.fastForward(10100)
  await expect(page.getByRole('alert')).toContainText('请求超时')
  await page.route('**/api/orders', route => route.fulfill({ status: 502, contentType: 'text/html', body: '<h1>private proxy error</h1>' }))
  await page.getByRole('button', { name: '重试确认原订单' }).click()
  await expect(page.getByRole('alert')).toContainText('服务暂时不可用')
  await expect(page.getByRole('alert')).not.toContainText('private proxy')
  await expect(page.getByLabel('上车地点名称')).toBeDisabled()
})

test('草稿按账号隔离，其他账号看不到地点', async ({ page }) => {
  await preset(page)
  await page.route('**/api/users/me', route => route.fulfill({ json: { code: 0, data: { userId: 99, phone: '13900139000', role: 'PASSENGER' } } }))
  await page.reload()
  await expect(page.getByLabel('上车地点名称')).toHaveValue('')
})

test('列表空态、分页、错误保留与详情入口', async ({ page }) => {
  let fail = false
  await page.route('**/api/orders/mine?*', route => {
    const pageNumber = Number(new URL(route.request().url()).searchParams.get('page'))
    return fail ? route.abort() : route.fulfill({ json: { code: 0, data: { page: pageNumber, size: 10, total: 11, items: [fixture('PAID', pageNumber === 1 ? 1601 : 1602)] } } })
  })
  await page.goto('/passenger/orders')
  await expect(page.getByText('第 1 页 / 共 2 页')).toBeVisible()
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.getByText('订单 #1602')).toBeVisible()
  await expect(page.getByRole('button', { name: '下一页' })).toBeDisabled()
  fail = true
  await page.getByRole('button', { name: '刷新订单' }).click()
  await expect(page.getByRole('alert')).toContainText('无法连接')
  await expect(page.getByText('订单 #1602')).toBeVisible()
  fail = false
  await page.getByRole('button', { name: '上一页' }).click()
  await expect(page.getByText('订单 #1601')).toBeVisible()
  await page.screenshot({ path: '../logs/day16-desktop-list.png', fullPage: true })
  await page.route('**/api/orders/mine?*', route => route.fulfill({ json: { code: 0, data: { page: 1, size: 10, total: 0, items: [] } } }))
  await page.getByRole('button', { name: '刷新订单' }).click()
  await expect(page.getByRole('heading', { name: '还没有这一程的记录' })).toBeVisible()
})

test('翻页失败保留当前页，刷新与再次翻页使用正确页码', async ({ page }) => {
  let rejectNextPage = true
  const requested: number[] = []
  await page.route('**/api/orders/mine?*', route => {
    const number = Number(new URL(route.request().url()).searchParams.get('page'))
    requested.push(number)
    if (number === 2 && rejectNextPage) return route.abort()
    return route.fulfill({ json: { code: 0, data: { page: number, size: 10, total: 11,
      items: [fixture('PAID', number === 1 ? 1601 : 1602)] } } })
  })
  await page.goto('/passenger/orders')
  await expect(page.getByText('订单 #1601')).toBeVisible()
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.getByRole('alert')).toContainText('无法连接')
  await expect(page.getByText('第 1 页 / 共 2 页')).toBeVisible()
  await expect(page.getByRole('button', { name: '上一页' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '下一页' })).toBeEnabled()
  await page.getByRole('button', { name: '刷新订单' }).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  expect(requested.at(-1)).toBe(1)
  rejectNextPage = false
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.getByText('订单 #1602')).toBeVisible()
  expect(requested.at(-1)).toBe(2)
})

test.describe('订单时间使用浏览器时区', () => {
  test.use({ timezoneId: 'Asia/Shanghai' })
  test('UTC 无偏移、Z 和显式偏移时间显示为同一本地时间', async ({ page }) => {
    for (const createdAt of ['2026-11-05T04:15:32', '2026-11-05T04:15:32Z', '2026-11-05T12:15:32+08:00']) {
      const order = { ...fixture('PAID'), createdAt, updatedAt: createdAt }
      await page.route('**/api/orders/mine?*', route => route.fulfill({ json: { code: 0,
        data: { page: 1, size: 10, total: 1, items: [order] } } }))
      await page.route('**/api/orders/1601', route => route.fulfill({ json: { code: 0, data: order } }))
      await page.goto('/passenger/orders')
      await expect(page.locator('time')).toHaveText('2026-11-05 12:15:32')
      await page.getByRole('link', { name: /订单 #1601/ }).click()
      await expect(page.locator('.order-metadata')).toContainText('2026-11-05 12:15:32')
    }
  })
})

for (const status of ['WAITING', 'ACCEPTED', 'IN_SERVICE', 'FINISHED', 'PAID', 'CANCELLED'] as OrderStatus[]) {
  test(`${status} 仅展示合法操作按钮`, async ({ page }) => {
    await page.route('**/api/orders/1601', route => route.fulfill({ json: { code: 0, data: fixture(status) } }))
    await page.goto('/passenger/orders/1601')
    await expect(page.getByRole('heading', { name: '天津站' })).toBeVisible()
    await expect(page.getByRole('button', { name: '取消订单', exact: true })).toHaveCount(status === 'WAITING' ? 1 : 0)
    await expect(page.getByRole('button', { name: '模拟支付', exact: true })).toHaveCount(status === 'FINISHED' ? 1 : 0)
  })
}

for (const [initial, action, button, final] of [['WAITING', 'cancel', '取消', 'CANCELLED'], ['FINISHED', 'pay-demo', '模拟支付', 'PAID']] as const) {
  test(`${button} 二次确认、防重复、以服务端结果更新`, async ({ page }) => {
    let status: OrderStatus = initial
    let writes = 0
    await page.route('**/api/orders/1601', route => route.fulfill({ json: { code: 0, data: fixture(status) } }))
    await page.route(`**/api/orders/1601/${action}`, async route => {
      writes++
      await new Promise(resolve => setTimeout(resolve, 300))
      status = final
      await route.fulfill({ json: { code: 0, data: fixture(status) } })
    })
    await page.goto('/passenger/orders/1601')
    await page.getByRole('button', { name: button === '取消' ? '取消订单' : button, exact: true }).click()
    await page.getByRole('button', { name: '暂不操作' }).click()
    expect(writes).toBe(0)
    await page.getByRole('button', { name: button === '取消' ? '取消订单' : button, exact: true }).click()
    await page.getByRole('button', { name: `确认${button}`, exact: true }).click()
    await expect(page.getByRole('button', { name: '正在处理' })).toBeDisabled()
    await expect(page.getByText(final === 'PAID' ? '已支付' : '已取消', { exact: true })).toBeVisible()
    expect(writes).toBe(1)
  })
}

test('取消竞争 409 自动刷新，不将旧状态留在页面', async ({ page }) => {
  let status: OrderStatus = 'WAITING'
  await page.route('**/api/orders/1601', route => route.fulfill({ json: { code: 0, data: fixture(status) } }))
  await page.route('**/api/orders/1601/cancel', route => { status = 'ACCEPTED'; return route.fulfill({ status: 409, json: { code: 'ORDER_NOT_WAITING' } }) })
  await page.goto('/passenger/orders/1601')
  await page.getByRole('button', { name: '取消订单' }).click()
  await page.getByRole('button', { name: '确认取消', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('已不在等待状态')
  await expect(page.getByText('司机已接单', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '取消订单' })).toHaveCount(0)
})

test('轮询同步服务完成，终态停止，离开后不再请求', async ({ page }) => {
  await page.clock.install()
  let reads = 0
  let status: OrderStatus = 'IN_SERVICE'
  await page.route('**/api/orders/1601', route => { reads++; return route.fulfill({ json: { code: 0, data: fixture(status) } }) })
  await page.goto('/passenger/orders/1601')
  await expect(page.getByText('服务中', { exact: true })).toBeVisible()
  status = 'FINISHED'
  await page.clock.fastForward(5100)
  await expect(page.getByRole('button', { name: '模拟支付', exact: true })).toBeVisible()
  status = 'PAID'
  await page.clock.fastForward(5100)
  await expect(page.getByText('已支付', { exact: true })).toBeVisible()
  const finalReads = reads
  await page.clock.fastForward(20000)
  expect(reads).toBe(finalReads)
  await page.getByRole('link', { name: '预约代驾' }).first().click()
  await page.clock.fastForward(20000)
  expect(reads).toBe(finalReads)
})

test('慢轮询不重叠，写操作成功后旧读取不能覆盖终态', async ({ page }) => {
  await page.clock.install()
  let reads = 0
  let release = () => {}
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/orders/1601', async route => {
    reads++
    if (reads > 1) await gate
    await route.fulfill({ json: { code: 0, data: fixture('WAITING') } })
  })
  await page.route('**/api/orders/1601/cancel', route => route.fulfill({ json: { code: 0, data: fixture('CANCELLED') } }))
  await page.goto('/passenger/orders/1601')
  await expect(page.locator('.status-badge')).toHaveText('等待接单')
  await page.clock.fastForward(5100)
  await expect.poll(() => reads).toBe(2)
  await page.clock.fastForward(6000)
  expect(reads).toBe(2)
  await page.getByRole('button', { name: '取消订单', exact: true }).click()
  await page.getByRole('button', { name: '确认取消', exact: true }).click()
  await expect(page.locator('.status-badge')).toHaveText('已取消')
  release()
  await expect(page.getByRole('button', { name: '刷新状态' })).toBeEnabled()
  await page.clock.fastForward(15000)
  await expect(page.locator('.status-badge')).toHaveText('已取消')
  expect(reads).toBe(2)
})

test('列表隐藏暂停、恢复刷新、离开停止轮询', async ({ page }) => {
  await page.clock.install()
  let reads = 0
  await page.route('**/api/orders/mine?*', route => { reads++; return route.fulfill({ json: { code: 0, data: { page: 1, size: 10, total: 0, items: [] } } }) })
  await page.goto('/passenger/orders')
  await expect(page.getByRole('heading', { name: '还没有这一程的记录' })).toBeVisible()
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, value: true }); document.dispatchEvent(new Event('visibilitychange')) })
  await page.clock.fastForward(20000)
  expect(reads).toBe(1)
  await page.evaluate(() => { Object.defineProperty(document, 'hidden', { configurable: true, value: false }); document.dispatchEvent(new Event('visibilitychange')) })
  await expect.poll(() => reads).toBe(2)
  await expect(page.getByRole('button', { name: '刷新订单' })).toBeEnabled()
  await page.getByRole('link', { name: '预约代驾' }).first().click()
  await page.clock.fastForward(20000)
  expect(reads).toBe(2)
})

for (const code of [403, 404]) {
  test(`详情 ${code} 有明确提示并停止轮询`, async ({ page }) => {
    let reads = 0
    await page.clock.install()
    await page.route('**/api/orders/1601', route => { reads++; return route.fulfill({ status: code, json: { code: code === 403 ? 'FORBIDDEN' : 'NOT_FOUND' } }) })
    await page.goto('/passenger/orders/1601')
    await expect(page.getByRole('alert')).toContainText(code === 403 ? '权限' : '不存在')
    await page.clock.fastForward(20000)
    expect(reads).toBe(1)
  })
}

test('订单 401 清理会话；非法编号不发送请求', async ({ page }) => {
  await page.goto('/passenger/orders/invalid')
  await expect(page.getByRole('alert')).toContainText('编号无效')
  await page.route('**/api/orders/1601', route => route.fulfill({ status: 401, json: { code: 'UNAUTHORIZED' } }))
  await page.goto('/passenger/orders/1601')
  await expect(page).toHaveURL(/\/login/)
  await expect(page.getByRole('status')).toContainText('登录已失效')
})

test('司机不能进入任一乘客订单页面', async ({ page }) => {
  await page.route('**/api/users/me', route => route.fulfill({ json: { code: 0, data: { userId: 17, phone: '13900139000', role: 'DRIVER' } } }))
  for (const path of ['/passenger/orders/new', '/passenger/orders', '/passenger/orders/1601']) {
    await page.goto(path)
    await expect(page).toHaveURL('/forbidden')
  }
})
