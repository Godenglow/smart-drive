// Decode each recorded role session and capture a reviewable frame.
const { chromium } = require('../web-app/node_modules/playwright')
const fs = require('node:fs')
const path = require('node:path')
const { pathToFileURL } = require('node:url')

;(async () => {
  const directory = path.resolve(process.argv[2] || '')
  const allowed = path.resolve(__dirname, '../logs/step22') + path.sep
  if (!directory.startsWith(allowed)) throw new Error('Expected a Step22 evidence directory')
  const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome' })
  const videos = []
  try {
    for (const name of ['passenger', 'driver-a', 'driver-b']) {
      const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
      await page.goto(pathToFileURL(path.join(directory, `${name}.webm`)).href)
      await page.waitForFunction(() => document.querySelector('video')?.readyState >= 2)
      const metadata = await page.evaluate(async () => {
        const video = document.querySelector('video')
        video.pause()
        await new Promise((resolve, reject) => {
          const timer = setTimeout(() => reject(new Error('Video seek timeout')), 10000)
          video.addEventListener('seeked', () => { clearTimeout(timer); resolve() }, { once: true })
          video.currentTime = video.duration * 0.7
        })
        return { duration: video.duration, width: video.videoWidth, height: video.videoHeight,
          sampleTime: video.currentTime, error: video.error?.message || null }
      })
      if (!Number.isFinite(metadata.duration) || metadata.duration < 10 ||
          metadata.width !== 1440 || metadata.height !== 1000 || metadata.error) throw new Error(`Invalid video: ${name}`)
      await page.screenshot({ path: path.join(directory, `${name}-sample.png`) })
      videos.push({ name, ...metadata, bytes: fs.statSync(path.join(directory, `${name}.webm`)).size })
      await page.close()
    }
    fs.writeFileSync(path.join(directory, 'video-qa.json'), JSON.stringify({ passed: true, videos }, null, 2))
    console.log(JSON.stringify({ passed: true, videos }))
  } finally { await browser.close() }
})().catch(error => { console.error(error.message); process.exitCode = 1 })
