<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ApiError } from '../api'
import { money, orderApi, statusNames, terminal, type Order } from '../orders'
import { usePolling } from '../polling'
const id = String(useRoute().params.id)
const validId = /^[1-9]\d{0,18}$/.test(id)
const order = ref<Order | null>(null)
const busy = ref(false)
const confirm = ref(false)
const actionError = ref('')
const actionTrace = ref('')
let revision = 0
let blocked = !validId
const hints = { WAITING: '正在等待附近司机接单，未接单前可取消。', ACCEPTED: '司机已接单，请耐心等候。', IN_SERVICE: '司机正在为你服务，愿这一程安心。', FINISHED: '服务已完成，请确认并模拟支付。', PAID: '本次模拟支付完成，感谢同行。', CANCELLED: '订单已取消，期待下一次同行。' }
const action = computed(() => order.value?.status === 'WAITING' ? 'cancel' : order.value?.status === 'FINISHED' ? 'pay-demo' : null)
const { loading, error, trace, updatedAt, refresh } = usePolling(async signal => {
  if (!validId) throw new ApiError('订单编号无效。', 400)
  const version = revision
  try {
    const result = await orderApi.detail(id, signal)
    if (!signal.aborted && version === revision && !busy.value) { order.value = result; blocked = false }
  } catch (failure) {
    if (failure instanceof ApiError && [403, 404].includes(failure.status)) { blocked = true; order.value = null }
    throw failure
  }
}, () => !blocked && (!order.value || !terminal(order.value.status)))
async function perform() {
  if (busy.value || !action.value || !confirm.value) return
  const target = action.value
  busy.value = true; revision++; actionError.value = ''; actionTrace.value = ''
  try { order.value = await orderApi.action(id, target); confirm.value = false }
  catch (failure) {
    actionError.value = failure instanceof Error ? failure.message : '操作失败，请刷新订单后重试。'
    actionTrace.value = failure instanceof ApiError ? failure.requestId : ''
    confirm.value = false
  } finally { busy.value = false; revision++; void refresh() }
}
</script>

<template>
  <main class="workspace order-workspace">
    <nav class="order-nav" aria-label="乘客导航"><RouterLink to="/passenger/orders">← 我的订单</RouterLink><RouterLink to="/passenger/orders/new">预约代驾 ↗</RouterLink></nav>
    <div class="page-heading"><span class="section-kicker">JOURNEY DETAILS</span><h1>行程详情<span class="heading-dot">.</span></h1><p class="muted">订单 #{{ id }}</p></div>
    <div class="list-toolbar"><span class="muted">{{ updatedAt ? `最近更新 ${updatedAt}` : '正在获取订单' }} · {{ order && terminal(order.status) ? '行程已结束' : '每 5 秒同步进度' }}</span><button class="secondary-button" :disabled="loading || busy" @click="refresh">{{ loading ? '刷新中…' : '刷新状态' }}</button></div>
    <div v-if="error" class="error-box" role="alert">{{ error }}<small v-if="trace">请求编号：{{ trace }}</small></div>
    <div v-if="actionError" class="error-box" role="alert">{{ actionError }}<small v-if="actionTrace">请求编号：{{ actionTrace }}</small></div>
    <div v-if="order" class="order-layout">
      <section class="order-panel"><span class="status-badge" :class="order.status">{{ statusNames[order.status] || order.status }}</span><h2 class="detail-hint">{{ hints[order.status] }}</h2>
        <div class="place-summary" v-for="(place, index) in [order.pickup, order.dropoff]" :key="index"><span class="place-dot" :class="{ destination: index === 1 }"></span><div><span class="muted">{{ index === 0 ? '上车地点' : '目的地点' }}</span><h2>{{ place.name }}</h2><p class="muted">经度 {{ place.lng }} · 纬度 {{ place.lat }}</p></div></div>
        <dl class="order-metadata"><div><dt>创建时间</dt><dd>{{ order.createdAt.replace('T', ' ') }}</dd></div><div><dt>更新时间</dt><dd>{{ order.updatedAt.replace('T', ' ') }}</dd></div><div><dt>司机编号</dt><dd>{{ order.driverId ?? '等待接单' }}</dd></div></dl>
      </section>
      <aside class="order-panel fare-panel"><span class="section-kicker">演示费用</span><div class="fare">¥ {{ money(order.amount) }}</div><p class="muted">固定演示金额，无真实资金流转。</p>
        <template v-if="action"><button v-if="!confirm" class="primary-button" :disabled="busy || Boolean(error)" @click="confirm = true">{{ action === 'cancel' ? '取消订单' : '模拟支付' }}<span aria-hidden="true">↗</span></button><div v-else class="notice" role="status"><p>{{ action === 'cancel' ? '确定取消这笔等待中的订单？' : `确认模拟支付 ¥ ${money(order.amount)}？不会扣款。` }}</p><div class="confirmation-actions"><button class="secondary-button" :disabled="busy" @click="confirm = false">暂不操作</button><button class="secondary-button" :disabled="busy || Boolean(error)" @click="perform">{{ busy ? '正在处理…' : action === 'cancel' ? '确认取消' : '确认模拟支付' }}</button></div></div></template>
        <p v-if="order.status === 'PAID'" class="notice success" role="status">已完成模拟支付，无真实扣款。</p><RouterLink class="text-button back-link" to="/passenger/orders">返回我的订单 →</RouterLink>
      </aside>
    </div>
  </main>
</template>
