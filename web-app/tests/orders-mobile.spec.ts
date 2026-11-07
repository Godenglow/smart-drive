import { test, expect } from '@playwright/test'
import { fixture, signedIn, preset } from './order-helpers'

test('移动端下单、列表和支付详情无横向溢出且可操作', async ({ page }) => {
  await signedIn(page)
  await page.route('**/api/orders/mine?*', route => route.fulfill({ json: { code: 0, data: { page: 1, size: 10, total: 1, items: [fixture('FINISHED')] } } }))
  await page.route('**/api/orders/1601', route => route.fulfill({ json: { code: 0, data: fixture('FINISHED') } }))
  await preset(page)
  await expect(page.getByRole('button', { name: '确认下单' })).toBeVisible()
  await page.screenshot({ path: '../logs/step16-mobile-create.png', fullPage: true })
  for (const path of ['/passenger/orders/new', '/passenger/orders', '/passenger/orders/1601']) {
    await page.goto(path)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  }
  await page.getByRole('button', { name: '模拟支付', exact: true }).click()
  await expect(page.getByRole('button', { name: '确认模拟支付', exact: true })).toBeVisible()
  await page.screenshot({ path: '../logs/step16-mobile-detail.png', fullPage: true })
})
