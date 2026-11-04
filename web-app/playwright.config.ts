import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  fullyParallel: true,
  workers: 4,
  reporter: [['list'], ['json', { outputFile: process.env.PLAYWRIGHT_JSON_OUTPUT || '../logs/browser-results.json' }]],
  use: { baseURL: 'http://127.0.0.1:5173', channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome', screenshot: 'only-on-failure', trace: 'off' },
  webServer: { command: 'npm run dev', url: 'http://127.0.0.1:5173', reuseExistingServer: !process.env.CI, timeout: 30_000 },
  projects: [
    { name: 'chromium', testMatch: 'auth.spec.ts', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', testMatch: 'mobile.spec.ts', use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' } },
    { name: 'live', testMatch: 'live.spec.ts', use: { ...devices['Desktop Chrome'] } },
    { name: 'orders', testMatch: 'orders.spec.ts', use: { ...devices['Desktop Chrome'] } },
    { name: 'orders-mobile', testMatch: 'orders-mobile.spec.ts', use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' } },
    { name: 'orders-live', testMatch: 'orders-live.spec.ts', use: { ...devices['Desktop Chrome'] } },
    { name: 'drivers', testMatch: 'drivers.spec.ts', use: { ...devices['Desktop Chrome'] } },
    { name: 'drivers-mobile', testMatch: 'drivers-mobile.spec.ts', use: { ...devices['iPhone 13'], defaultBrowserType: 'chromium' } },
    { name: 'drivers-live', testMatch: 'drivers-live.spec.ts', use: { ...devices['Desktop Chrome'] } },
    { name: 'journey', testMatch: 'journey.spec.ts', use: { ...devices['Desktop Chrome'] } },
    { name: 'journey-live', testMatch: 'journey-live.spec.ts', use: { ...devices['Desktop Chrome'] } },
  ],
})
