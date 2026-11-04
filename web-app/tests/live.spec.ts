import { test, expect } from '@playwright/test'
import { appendFileSync, mkdirSync } from 'node:fs'
import { randomInt } from 'node:crypto'

test.skip(process.env.SMARTDRIVE_LIVE_TEST !== 'true', '通过 scripts/verify-day15.ps1 显式启用真实 Gateway/MySQL 验收')

for (const role of ['PASSENGER', 'DRIVER']) {
  test(`真实网关：${role} 浏览器注册、登录、刷新、越权、退出`, async ({ page }) => {
    const phone = '199' + String(randomInt(0, 100_000_000)).padStart(8, '0')
    const password = 'Day15!Browser-' + randomInt(10000, 99999)
    await page.goto('/register')
    await page.getByRole('radio', { name: role === 'DRIVER' ? /司机/ : /乘客/ }).check()
    await page.getByLabel('手机号', { exact: true }).fill(phone)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByLabel('确认密码').fill(password)
    // 保存精确临时账号 ID，供 PowerShell finally 清理；不记录密码或 token。
    const registration = page.waitForResponse(response => response.url().endsWith('/api/auth/register'))
    await page.getByRole('button', { name: '创建账号' }).click()
    const response = await registration
    const body = await response.json()
    if (body.data?.userId) {
      mkdirSync('../logs', { recursive: true })
      appendFileSync('../logs/day15-created-accounts.jsonl', JSON.stringify({ userId: body.data.userId, phone }) + '\n')
    }
    expect(response.status()).toBe(200)
    await expect(page.getByRole('status')).toContainText('注册成功')
    await page.getByLabel('密码', { exact: true }).fill('wrong-password')
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText('手机号或密码错误')
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    const home = role === 'DRIVER' ? '/driver' : '/passenger'
    await expect(page).toHaveURL(home)
    await expect(page.getByRole('heading', { name: role === 'DRIVER' ? '司机身份已就绪' : '乘客身份已就绪' })).toBeVisible()
    await page.reload()
    await expect(page.getByRole('heading', { name: role === 'DRIVER' ? '司机身份已就绪' : '乘客身份已就绪' })).toBeVisible()
    await page.screenshot({ path: `../logs/day15-live-${role.toLowerCase()}.png`, fullPage: true })
    // 后端仍须独立拦截角色权限，不依赖前端守卫。
    const denied = await page.evaluate(async currentRole => {
      const token = JSON.parse(sessionStorage.getItem('smartdrive.session')!).token
      const response = await fetch(currentRole === 'DRIVER' ? '/api/orders' : '/api/drivers/me/notices', { method: currentRole === 'DRIVER' ? 'POST' : 'GET', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, ...(currentRole === 'DRIVER' ? { body: '{}' } : {}) })
      return response.status
    }, role)
    expect(denied).toBe(403)
    await page.goto(role === 'DRIVER' ? '/passenger' : '/driver')
    await expect(page.getByRole('heading', { name: '当前身份无法访问此页面' })).toBeVisible()
    await page.getByRole('button', { name: '退出登录' }).click()
    await expect(page).toHaveURL('/login')
    // 重复注册使用真实唯一约束。
    await page.goto('/register')
    await page.getByLabel('手机号', { exact: true }).fill(phone)
    await page.getByLabel('密码', { exact: true }).fill(password)
    await page.getByLabel('确认密码').fill(password)
    await page.getByRole('button', { name: '创建账号' }).click()
    await expect(page.getByRole('alert')).toContainText('已注册')
    await page.goto(home)
    await expect(page).toHaveURL(/\/login/)
  })
}
