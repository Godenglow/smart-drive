<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ApiError } from '../api'
import { money, orderApi, orderTime, statusNames, terminal, type Order } from '../orders'
import { usePolling } from '../polling'
import { session } from '../session'
const driver = session.account?.role === 'DRIVER'
const base = driver ? '/driver' : '/passenger'
const id = String(useRoute().params.id)
const validId = /^[1-9]\d{0,18}$/.test(id)
const order = ref<Order | null>(null)
const busy = ref(false)
const reconciling = ref(false)
type Action = 'accept' | 'start' | 'finish' | 'cancel' | 'pay-demo'
const confirm = ref<Action | null>(null)
const actionError = ref('')
const actionTrace = ref('')
let revision = 0
let blocked = !validId
const hints = { WAITING: '正在等待附近司机接单，未接单前可取消。', ACCEPTED: '司机已接单，请耐心等候。', IN_SERVICE: '司机正在为你服务，愿这一程安心。', FINISHED: '服务已完成，请确认并模拟支付。', PAID: '本次模拟支付完成，感谢同行。', CANCELLED: '订单已取消，期待下一次同行。' }
const action = computed<Action | null>(() => {
  if (!driver) return order.value?.status === 'WAITING' ? 'cancel' : order.value?.status === 'FINISHED' ? 'pay-demo' : null
  if (order.value?.status === 'WAITING') return 'accept'
  if (order.value?.driverId !== session.account?.userId) return null
  return order.value?.status === 'ACCEPTED' ? 'start' : order.value?.status === 'IN_SERVICE' ? 'finish' : null
})
const actionNames = { accept: '抢单', start: '开始服务', finish: '完成服务', cancel: '取消订单', 'pay-demo': '模拟支付' }
const confirmNames = { ...actionNames, cancel: '取消' }
const confirmations = { accept: '确认抢下这笔订单？请确保你已上线且没有其他进行中的订单。', start: '确认乘客已就绪，开始本次服务？', finish: '确认本次服务已完成？完成后释放接单占用，等待乘客模拟支付。', cancel: '确定取消这笔等待中的订单？', 'pay-demo': '确认模拟支付？不会扣款。' }
const driverHints = { WAITING: '候选通知仅供参考，点击抢单后以服务端结果为准。', ACCEPTED: '你已接单，乘客就绪后可开始服务。', IN_SERVICE: '服务进行中，到达目的地后请确认完成。', FINISHED: '服务已完成，等待乘客模拟支付。你可以继续接单。', PAID: '乘客已完成模拟支付，感谢认真服务。', CANCELLED: '订单已取消，请查看其他待抢订单。' }
const hint = computed(() => {
  if (!order.value) return ''
  if (!driver) return hints[order.value.status]
  if (['ACCEPTED', 'IN_SERVICE', 'FINISHED', 'PAID'].includes(order.value.status) && order.value.driverId !== session.account?.userId) return '订单已被其他司机接走，请返回工作台查看其他订单。'
  return driverHints[order.value.status]
})
watch(action, () => { confirm.value = null })
const { loading, error, trace, updatedAt, refresh, refreshLatest } = usePolling(async signal => {
  if (!validId) throw new ApiError('订单编号无效。', 400)
  const version = revision
  try {
    const result = await orderApi.detail(id, signal)
    if (!signal.aborted && version === revision && !busy.value) { order.value = result; blocked = false; reconciling.value = false }
  } catch (failure) {
    // 动作发生前的慢读取（含失败）不能污染动作后的页面。
    if (signal.aborted || version !== revision || busy.value) return
    if (failure instanceof ApiError && [403, 404].includes(failure.status)) { blocked = true; order.value = null; reconciling.value = false }
    throw failure
  }
}, () => !blocked && (!order.value || !terminal(order.value.status)))
async function perform() {
  if (busy.value || reconciling.value || error.value || !action.value || confirm.value !== action.value) return
  const target = action.value
  busy.value = true; revision++; actionError.value = ''; actionTrace.value = ''
  try { order.value = await orderApi.action(id, target); confirm.value = null }
  catch (failure) {
    actionError.value = failure instanceof Error ? failure.message : '操作失败，请刷新订单后重试。'
    actionTrace.value = failure instanceof ApiError ? failure.requestId : ''
    confirm.value = null
    reconciling.value = true
  } finally { busy.value = false; revision++; void (reconciling.value ? refreshLatest() : refresh()) }
}
</script>

<template>
  <main class="workspace order-workspace">
    <nav class="order-nav" :aria-label="driver ? '司机导航' : '乘客导航'"><RouterLink :to="`${base}/orders`">← 我的订单</RouterLink><RouterLink :to="driver ? '/driver' : '/passenger/orders/new'">{{ driver ? '司机工作台 ↗' : '预约代驾 ↗' }}</RouterLink></nav>
    <div class="page-heading"><span class="section-kicker">JOURNEY DETAILS</span><h1>行程详情<span class="heading-dot">.</span></h1><p class="muted">订单 #{{ id }}</p></div>
    <div class="list-toolbar"><span class="muted">{{ updatedAt ? `最近更新 ${updatedAt}` : '正在获取订单' }} · {{ order && terminal(order.status) ? '行程已结束' : '每 5 秒同步进度' }}</span><button class="secondary-button" :disabled="loading || busy" @click="refresh">{{ loading ? '刷新中…' : '刷新状态' }}</button></div>
    <div v-if="error" class="error-box" role="alert">{{ error }}<small v-if="trace">请求编号：{{ trace }}</small></div>
    <div v-if="actionError" class="error-box" role="alert">{{ actionError }}<small v-if="actionTrace">请求编号：{{ actionTrace }}</small></div>
    <p v-if="reconciling" class="notice" role="status">操作结果待确认，正在同步服务端状态。同步成功后可继续操作；读取失败可点击“刷新状态”。</p>
    <div v-if="order" class="order-layout">
      <section class="order-panel"><span class="status-badge" :class="order.status">{{ statusNames[order.status] || order.status }}</span><h2 class="detail-hint">{{ hint }}</h2>
        <div class="place-summary" v-for="(place, index) in [order.pickup, order.dropoff]" :key="index"><span class="place-dot" :class="{ destination: index === 1 }"></span><div><span class="muted">{{ index === 0 ? '上车地点' : '目的地点' }}</span><h2>{{ place.name }}</h2><p class="muted">经度 {{ place.lng }} · 纬度 {{ place.lat }}</p></div></div>
        <dl class="order-metadata"><div><dt>创建时间</dt><dd>{{ orderTime(order.createdAt) }}</dd></div><div><dt>更新时间</dt><dd>{{ orderTime(order.updatedAt) }}</dd></div><div><dt>司机编号</dt><dd>{{ order.driverId ?? (order.status === 'WAITING' ? '等待接单' : '候选视图不展示') }}</dd></div></dl>
      </section>
      <aside class="order-panel fare-panel"><span class="section-kicker">演示费用</span><div class="fare">¥ {{ money(order.amount) }}</div><p class="muted">固定演示金额，无真实资金流转。</p>
        <template v-if="action"><button v-if="!confirm" class="primary-button" :disabled="busy || reconciling || Boolean(error)" @click="confirm = action">{{ actionNames[action] }}<span aria-hidden="true">↗</span></button><div v-else class="notice" role="status"><p>{{ confirmations[confirm] }}</p><div class="confirmation-actions"><button class="secondary-button" :disabled="busy" @click="confirm = null">暂不操作</button><button class="secondary-button" :disabled="busy || reconciling || Boolean(error)" @click="perform">{{ busy ? '正在处理…' : `确认${confirmNames[confirm]}` }}</button></div></div></template>
        <p v-if="order.status === 'PAID'" class="notice success" role="status">已完成模拟支付，无真实扣款。</p><RouterLink class="text-button back-link" :to="`${base}/orders`">返回我的订单 →</RouterLink>
      </aside>
    </div>
  </main>
</template>
