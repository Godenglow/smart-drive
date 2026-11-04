import { type Page } from '@playwright/test'
import { fixture, signedIn } from './order-helpers'
export async function driverMock(page: Page) {
  await signedIn(page, 'DRIVER', 17)
  const model = { profile: { userId: 17, displayName: '安心司机', createdAt: '2026-11-04T12:00:00' } as { userId: number; displayName: string; createdAt: string } | null,
    online: false, orders: [fixture('WAITING', 1701)], notices: [1701], calls: [] as { path: string; body: any }[] }
  await page.route('**/api/drivers/me/**', async route => {
    const path = new URL(route.request().url()).pathname
    const body = route.request().postDataJSON()
    if (route.request().method() === 'PUT') model.calls.push({ path, body })
    if (path.endsWith('/profile')) model.profile = { userId: 17, displayName: body.displayName, createdAt: '2026-11-04T12:00:00' }
    if (path.endsWith('/online')) model.online = true
    if (path.endsWith('/offline')) model.online = false
    const data = path.endsWith('/status') ? { profile: model.profile, online: model.online } : path.endsWith('/notices') ? model.notices : path.endsWith('/profile') ? model.profile : { online: model.online, updatedAt: new Date().toISOString() }
    await route.fulfill({ json: { code: 0, data } })
  })
  await page.route('**/api/orders/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/mine')) { await route.fulfill({ json: { code: 0, data: { page: 1, size: 10, total: model.orders.length, items: model.orders } } }); return }
    const id = Number(path.split('/')[3])
    const order = model.orders.find(item => item.id === id)
    if (!order) { await route.fulfill({ status: 404, json: { code: 'NOT_FOUND' } }); return }
    if (route.request().method() === 'POST') {
      model.calls.push({ path, body: null })
      order.status = path.endsWith('/accept') ? 'ACCEPTED' : path.endsWith('/start') ? 'IN_SERVICE' : 'FINISHED'
      order.driverId = 17
    }
    await route.fulfill({ json: { code: 0, data: order } })
  })
  return model
}
