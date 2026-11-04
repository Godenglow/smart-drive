import { test, expect } from '@playwright/test'

for (const path of ['/login', '/register']) {
  test(`移动端 ${path} 表单可用且无横向溢出`, async ({ page }) => {
    await page.goto(path)
    await expect(page.getByLabel('手机号', { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: path === '/login' ? '登录' : '创建账号', exact: true })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `../logs/day15-mobile-${path.slice(1)}.png`, fullPage: true })
  })
}
