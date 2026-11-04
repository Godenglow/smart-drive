import { onMounted, onUnmounted, ref } from 'vue'
import { ApiError } from './api'

/** 完成后再计时，避免慢请求重叠；隐藏或离开页面时中止读取。 */
export function usePolling(load: (signal: AbortSignal) => Promise<void>, shouldPoll: () => boolean = () => true, interval = 5000) {
  const loading = ref(false)
  const error = ref('')
  const trace = ref('')
  const updatedAt = ref('')
  let timer: number | undefined
  let controller: AbortController | undefined
  let disposed = false
  async function refresh() {
    if (disposed || document.hidden || loading.value) return
    window.clearTimeout(timer)
    loading.value = true
    const current = new AbortController()
    controller = current
    try {
      await load(current.signal)
      if (!disposed && !current.signal.aborted) {
        error.value = ''; trace.value = ''
        updatedAt.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
      }
    } catch (failure) {
      if (!disposed && !current.signal.aborted) {
        error.value = failure instanceof Error ? failure.message : '读取失败，请重试。'
        trace.value = failure instanceof ApiError ? failure.requestId : ''
      }
    } finally {
      loading.value = false
      if (!disposed && !document.hidden && shouldPoll()) timer = window.setTimeout(() => void refresh(), interval)
    }
  }
  function stop() { window.clearTimeout(timer); controller?.abort() }
  function visibility() { if (document.hidden) stop(); else void refresh() }
  onMounted(() => { document.addEventListener('visibilitychange', visibility); void refresh() })
  onUnmounted(() => { disposed = true; stop(); document.removeEventListener('visibilitychange', visibility) })
  return { loading, error, trace, updatedAt, refresh }
}
