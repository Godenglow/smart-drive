<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ApiError } from '../api'
import { driverApi, validLocation, type Location, type Profile } from '../drivers'
import { money, orderApi, type Order } from '../orders'
import { usePolling } from '../polling'
import { session } from '../session'
const profile = ref<Profile | null>(null)
const online = ref<boolean | null>(null)
const lng = ref<string | number>('117.215'), lat = ref<string | number>('39.136')
const storageKey = session.account?.userId == null ? null : `smartdrive.driver-location.${session.account.userId}`
let confirmed: Location | null = null
try {
  const saved = JSON.parse((storageKey ? sessionStorage.getItem(storageKey) : null) || 'null')
  if (saved && typeof saved.lng === 'number' && typeof saved.lat === 'number') confirmed = validLocation(String(saved.lng), String(saved.lat))
  if (confirmed) { lng.value = String(confirmed.lng); lat.value = String(confirmed.lat) }
} catch { /* 存储不可用时仍可在当前页面上线。 */ }
const working = ref(false)
const actionError = ref(''), actionTrace = ref(''), message = ref(''), heartbeatAt = ref('')
const cards = ref<Order[]>([])
const unavailable = ref(0)
let revision = 0, disposed = false
onUnmounted(() => { disposed = true; revision++ })
const { loading, error, trace, updatedAt, refresh } = usePolling(async signal => {
  if (working.value) return
  const version = revision
  const state = await driverApi.state(signal)
  if (signal.aborted || version !== revision) return
  profile.value = state.profile; online.value = state.online
  const ids = await driverApi.notices(signal)
  const results = await Promise.allSettled(ids.map(id => orderApi.detail(String(id), signal)))
  if (signal.aborted || version !== revision) return
  cards.value = results.flatMap(result => result.status === 'fulfilled' && result.value.status === 'WAITING' ? [result.value] : [])
  unavailable.value = results.filter(result => result.status === 'rejected').length
})
function remember(point: Location) {
  confirmed = point
  try { if (storageKey) sessionStorage.setItem(storageKey, JSON.stringify(point)) } catch { /* 仅影响刷新后的自动心跳恢复。 */ }
}
async function mutate(action: 'online' | 'offline' | 'location', heartbeat = false, signal?: AbortSignal) {
  if (working.value || disposed) return
  const point = heartbeat ? confirmed : validLocation(lng.value, lat.value)
  actionError.value = ''; actionTrace.value = ''; message.value = ''
  if (action !== 'offline' && !point) { actionError.value = '请填写有效经纬度：经度 -180–180，纬度 ±85.05112878。'; return }
  working.value = true; revision++
  try {
    const result = action === 'offline' ? await driverApi.offline() : action === 'online' ? await driverApi.online(point!) : await driverApi.location(point!, signal)
    if (disposed || signal?.aborted) return
    online.value = result.online
    if (action !== 'offline') { remember(point!); heartbeatAt.value = new Date(result.updatedAt).toLocaleTimeString('zh-CN', { hour12: false }) }
    message.value = action === 'offline' ? '已离线，停止位置心跳。' : heartbeat ? '' : action === 'online' ? '已上线，可以接收附近订单。' : '位置已更新。'
  } catch (failure) {
    if (disposed || signal?.aborted) return
    online.value = failure instanceof ApiError && failure.code === 'DRIVER_OFFLINE' ? false : null
    actionError.value = failure instanceof Error ? failure.message : '操作失败，请重新读取状态。'
    actionTrace.value = failure instanceof ApiError ? failure.requestId : ''
  } finally { working.value = false; revision++ }
}
// 心跳只续期已经确认过的坐标；不把尚未提交的输入框内容当作当前位置。
usePolling(async signal => {
  if (online.value === true && confirmed) await mutate('location', true, signal)
}, () => true, 30000)
function preset(city: '天津' | '上海') {
  lng.value = city === '天津' ? '117.215' : '121.4737'; lat.value = city === '天津' ? '39.136' : '31.2304'
}
</script>
<template>
  <main class="workspace order-workspace">
    <nav class="order-nav" aria-label="司机导航"><RouterLink to="/driver/profile">司机资料</RouterLink><RouterLink to="/driver/orders">我的订单 →</RouterLink></nav>
    <div class="page-heading"><span class="section-kicker">READY FOR THE ROAD</span><h1>司机工作台<span class="heading-dot">.</span></h1><p class="muted">{{ profile ? `你好，${profile.displayName}。` : '欢迎来到 SmartDrive。' }}认真服务，让归途更安心。</p></div>
    <div class="list-toolbar"><span class="muted">{{ updatedAt ? `最近更新 ${updatedAt}` : '正在读取状态' }} · 每 5 秒同步待抢订单</span><button class="secondary-button" :disabled="loading || working" @click="refresh">刷新工作台</button></div>
    <div v-if="error" class="error-box" role="alert">{{ error }}<small v-if="trace">请求编号：{{ trace }}</small><span>暂时无法确认最新状态，请刷新后操作。</span></div>
    <div class="order-layout">
      <section class="order-panel"><h2>司机身份已就绪</h2><p class="muted">账号 #{{ session.account?.userId }} · {{ profile?.displayName || '尚未创建司机资料' }}</p>
        <div class="driver-status"><span class="status-badge" :class="online ? 'IN_SERVICE' : 'CANCELLED'">{{ online === null || error ? '在线状态待确认' : online ? '在线接单中' : '当前离线' }}</span><RouterLink class="text-button" to="/driver/profile">{{ profile ? '修改资料 →' : '先完善司机资料 →' }}</RouterLink></div>
        <div class="preset-row"><span>演示位置</span><button class="secondary-button" :disabled="working" @click="preset('天津')">天津站</button><button class="secondary-button" :disabled="working" @click="preset('上海')">上海人民广场</button></div>
        <div class="coordinate-grid"><label class="field">司机经度<input v-model="lng" type="number" step="any" :disabled="working"></label><label class="field">司机纬度<input v-model="lat" type="number" step="any" :disabled="working"></label></div>
        <p class="muted">坐标为演示位置，不是设备定位。修改后请点击更新位置。在线时每 30 秒续期已确认坐标。</p>
        <div v-if="actionError" class="error-box" role="alert">{{ actionError }}<small v-if="actionTrace">请求编号：{{ actionTrace }}</small></div>
        <p v-if="message" class="notice success" role="status">{{ message }}</p>
        <div class="confirmation-actions"><button v-if="online === false" class="secondary-button" :disabled="working || loading || !profile || Boolean(error)" @click="mutate('online')">上线接单</button><button v-if="online === true" class="secondary-button" :disabled="working || Boolean(error)" @click="mutate('location')">更新位置</button><button v-if="online !== false" class="secondary-button" :disabled="working" @click="mutate('offline')">离线休息</button></div>
        <p class="session-hint">{{ heartbeatAt ? `最近位置确认 ${heartbeatAt}。` : '' }}隐藏页面或离开工作台即停止心跳，在线标记最长 120 秒后过期；离开前可主动离线。返回时以服务端状态为准。</p>
      </section>
      <aside class="order-panel fare-panel"><span class="section-kicker">YOUR NEXT JOURNEY</span><h2>接单前的小准备</h2><p class="muted">保存资料 → 选择位置 → 上线接单。待抢提示来自异步匹配，出现后仍须检查订单当前状态。</p><p v-if="online && !confirmed" class="notice">本标签页没有已确认坐标，请更新位置以恢复自动心跳。</p><RouterLink class="primary-button" to="/driver/orders">查看我的订单 <span>↗</span></RouterLink><p class="muted">已接订单可在历史中继续开始或完成服务。</p></aside>
    </div>
    <div class="page-heading driver-notices"><span class="section-kicker">NEARBY REQUESTS</span><h2>待抢订单</h2><p class="muted">检查最近 20 条候选通知，仅展示仍在等待的订单。资格与归属由服务端抢单事务确认。</p></div>
    <p v-if="unavailable" class="notice" role="status">{{ unavailable }} 条通知详情暂时无法读取，下次刷新会重试。</p>
    <section v-if="!loading && !error && !cards.length" class="order-panel empty-state"><h2>暂时没有待抢订单</h2><p class="muted">上线后等待附近乘客下单，订单会自动出现在这里。</p></section>
    <div class="order-list"><RouterLink v-for="order in cards" :key="order.id" class="order-panel order-card" :to="`/driver/orders/${order.id}`"><div class="order-card-top"><span class="muted">订单 #{{ order.id }}</span><span class="status-badge WAITING">等待接单</span></div><h2>{{ order.pickup.name }} → {{ order.dropoff.name }}</h2><div class="order-card-bottom"><span>演示费用 ¥ {{ money(order.amount) }}</span><span>查看并抢单 ↗</span></div></RouterLink></div>
  </main>
</template>
