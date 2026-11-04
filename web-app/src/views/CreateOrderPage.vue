<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { ApiError } from '../api'
import { session } from '../session'
import { orderApi, type CreateOrder } from '../orders'

const router = useRouter()
const key = `smartdrive.order-draft.${session.account!.userId}`
const blank = (): CreateOrder => ({ requestId: crypto.randomUUID(), pickup: { name: '', lng: 117.215, lat: 39.136 }, dropoff: { name: '', lng: 117.174, lat: 39.104 } })
function restore(): { form: CreateOrder; submitted: boolean } {
  try {
    const saved = JSON.parse(sessionStorage.getItem(key) || 'null')
    if (saved && /^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(saved.form?.requestId) &&
      [saved.form.pickup, saved.form.dropoff].every(p => p && typeof p.name === 'string' && typeof p.lng === 'number' && typeof p.lat === 'number')) return saved
  } catch { /* 损坏草稿不影响页面使用。 */ }
  return { form: blank(), submitted: false }
}
const saved = restore()
const form = reactive(saved.form)
const submitted = ref(Boolean(saved.submitted))
const busy = ref(false)
const error = ref('')
const trace = ref('')
const locked = computed(() => busy.value || submitted.value)
function persist() {
  try { sessionStorage.setItem(key, JSON.stringify({ form, submitted: submitted.value })) } catch { /* 当前页仍保留内存草稿。 */ }
}
watch(form, persist, { deep: true, flush: 'sync' })
function preset(city: '天津' | '上海') {
  if (locked.value) return
  Object.assign(form, { requestId: crypto.randomUUID(), ...(city === '天津'
    ? { pickup: { name: '天津站', lng: 117.215, lat: 39.136 }, dropoff: { name: '南开大学', lng: 117.174, lat: 39.104 } }
    : { pickup: { name: '人民广场', lng: 121.4737, lat: 31.2304 }, dropoff: { name: '静安寺', lng: 121.445, lat: 31.223 } }) })
}
async function submit() {
  if (busy.value) return
  error.value = ''; trace.value = ''
  for (const [label, place] of [['上车', form.pickup], ['目的', form.dropoff]] as const) {
    if (!place.name.trim() || place.name.trim().length > 100) { error.value = `${label}地点名称需为 1–100 字。`; return }
    if (!Number.isFinite(place.lng) || place.lng < -180 || place.lng > 180) { error.value = `${label}地点经度需在 -180 至 180 之间。`; return }
    if (!Number.isFinite(place.lat) || place.lat < -85.05112878 || place.lat > 85.05112878) { error.value = `${label}地点纬度需在 -85.05112878 至 85.05112878 之间。`; return }
  }
  form.pickup.name = form.pickup.name.trim(); form.dropoff.name = form.dropoff.name.trim()
  busy.value = true; submitted.value = true; persist()
  try {
    const order = await orderApi.create(form)
    try { sessionStorage.removeItem(key) } catch { /* 禁用存储时仍正常进入详情。 */ }
    await router.replace(`/passenger/orders/${order.id}`)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : '下单失败，请重试。'
    trace.value = failure instanceof ApiError ? failure.requestId : ''
    // 明确的业务拒绝未创建订单；断网/超时/5xx 保留原请求内容与幂等键。
    if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500 && failure.status !== 408) submitted.value = false
    persist()
  } finally { busy.value = false }
}
</script>

<template>
  <main class="workspace order-workspace">
    <nav class="order-nav" aria-label="乘客导航"><RouterLink to="/passenger">乘客首页</RouterLink><RouterLink to="/passenger/orders">我的订单 ↗</RouterLink></nav>
    <div class="page-heading"><span class="section-kicker">A GOOD WAY HOME</span><h1>安排一程安心代驾<span class="heading-dot">.</span></h1><p class="muted">选择出发地与目的地，附近在线司机将收到接单提示。</p></div>
    <div class="order-layout">
      <section class="order-panel">
        <div class="preset-row"><span>演示路线</span><button class="secondary-button" :disabled="locked" @click="preset('天津')">天津站 → 南开大学</button><button class="secondary-button" :disabled="locked" @click="preset('上海')">人民广场 → 静安寺</button></div>
        <form novalidate @submit.prevent="submit">
          <fieldset v-for="(place, index) in [form.pickup, form.dropoff]" :key="index" class="place-fields" :disabled="locked">
            <legend><span class="place-dot" :class="{ destination: index === 1 }"></span>{{ index === 0 ? '上车地点' : '目的地点' }}</legend>
            <label class="field">{{ index === 0 ? '上车地点名称' : '目的地点名称' }}<input v-model="place.name" maxlength="100" placeholder="输入地点名称" required></label>
            <div class="coordinate-grid"><label class="field">{{ index === 0 ? '上车经度' : '目的地经度' }}<input v-model.number="place.lng" type="number" step="any" min="-180" max="180" required></label><label class="field">{{ index === 0 ? '上车纬度' : '目的地纬度' }}<input v-model.number="place.lat" type="number" step="any" min="-85.05112878" max="85.05112878" required></label></div>
          </fieldset>
          <div v-if="submitted" class="notice" role="status">提交结果待确认，路线已保留。请重试确认原订单，或前往“我的订单”查看。</div>
          <div v-if="error" class="error-box" role="alert">{{ error }}<small v-if="trace">请求编号：{{ trace }}</small></div>
          <button class="primary-button" :disabled="busy" type="submit">{{ busy ? '正在提交…' : submitted ? '重试确认原订单' : '确认下单' }}<span aria-hidden="true">↗</span></button>
        </form>
      </section>
      <aside class="order-panel fare-panel"><span class="section-kicker">这一程，从容出发</span><h2>演示费用</h2><div class="fare">¥ 50<span>.00</span></div><p class="muted">固定演示金额。服务完成后可模拟支付，不发生真实资金流转。</p><hr><p class="muted">演示预设仅填写坐标。需在上车点 5 km 内有在线司机才能下单；未接单前可取消。</p><p class="session-hint">草稿仅在当前标签页为当前账号保存。</p></aside>
    </div>
  </main>
</template>
