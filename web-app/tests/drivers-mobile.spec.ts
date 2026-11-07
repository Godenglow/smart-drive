import { test, expect } from '@playwright/test'
import { driverMock } from './driver-helpers'
for (const [path, name] of [['/driver', 'dashboard'], ['/driver/profile', 'profile'], ['/driver/orders', 'history'], ['/driver/orders/1701', 'detail']]) {
  test(`移动端司机 ${name} 可操作且无横向溢出`, async ({ page }) => {
    await driverMock(page)
    await page.goto(path!)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    if (name === 'dashboard') await expect(page.getByRole('button', { name: '上线接单' })).toBeEnabled()
    if (name === 'detail') await expect(page.getByRole('button', { name: '抢单', exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `../logs/step17-mobile-${name}.png`, fullPage: true })
  })
}
