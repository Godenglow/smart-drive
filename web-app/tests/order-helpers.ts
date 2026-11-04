import { type Page, expect } from '@playwright/test'
import type { Order, OrderStatus } from '../src/orders'
export function fixture(status: OrderStatus = 'WAITING', id = 1601): Order {
  return { id, requestId: 'fb6a9d1f-8db0-47d0-9f5d-59315be44fb5', passengerId: 16, driverId: null,
    pickup: { name: '天津站', lng: 117.215, lat: 39.136 }, dropoff: { name: '南开大学', lng: 117.174, lat: 39.104 },
    amount: 50, status, createdAt: '2026-11-04T10:00:00', updatedAt: '2026-11-04T10:00:00' }
}
export async function signedIn(page: Page, role = 'PASSENGER', userId = 16) {
  await page.addInitScript(() => {
    if (!sessionStorage.getItem('smartdrive.session')) sessionStorage.setItem('smartdrive.session', JSON.stringify({ token: 'order-test', expiresAt: Date.now() + 7200_000 }))
  })
  await page.route('**/api/users/me', route => route.fulfill({ json: { code: 0, data: { userId, phone: '13800138000', role } } }))
}
export async function preset(page: Page) {
  await page.goto('/passenger/orders/new')
  await page.getByRole('button', { name: '天津站 → 南开大学' }).click()
  await expect(page.getByLabel('上车地点名称')).toHaveValue('天津站')
}
