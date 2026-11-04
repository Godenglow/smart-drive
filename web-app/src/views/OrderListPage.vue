<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink } from 'vue-router'
import { money, orderApi, statusNames, type OrderPage } from '../orders'
import { usePolling } from '../polling'
import { session } from '../session'
const driver = session.account?.role === 'DRIVER'
const base = driver ? '/driver' : '/passenger'
const page = ref(1)
const data = ref<OrderPage | null>(null)
const { loading, error, trace, updatedAt, refresh } = usePolling(async signal => {
  const result = await orderApi.mine(page.value, signal)
  if (!signal.aborted) data.value = result
})
function turn(delta: number) { if (!loading.value) { page.value += delta; void refresh() } }
</script>

<template>
  <main class="workspace order-workspace">
    <nav class="order-nav" :aria-label="driver ? '司机导航' : '乘客导航'"><RouterLink :to="base">{{ driver ? '司机工作台' : '乘客首页' }}</RouterLink><RouterLink v-if="!driver" to="/passenger/orders/new">预约代驾 ↗</RouterLink></nav>
    <div class="page-heading"><span class="section-kicker">YOUR JOURNEYS</span><h1>我的订单<span class="heading-dot">.</span></h1><p class="muted">每一程，都有迹可循。每 5 秒自动更新，隐藏页面时暂停。</p></div>
    <div class="list-toolbar"><span class="muted">{{ updatedAt ? `最近更新 ${updatedAt}` : '正在获取订单' }}<template v-if="data"> · 共 {{ data.total }} 单</template></span><button class="secondary-button" :disabled="loading" @click="refresh">{{ loading ? '刷新中…' : '刷新订单' }}</button></div>
    <div v-if="error" class="error-box" role="alert">{{ error }}<small v-if="trace">请求编号：{{ trace }}</small><span>保留上次读取结果，请重试刷新。</span></div>
    <section v-if="data?.items.length === 0" class="order-panel empty-state"><h2>还没有这一程的记录</h2><p class="muted">{{ driver ? '接单后可在这里查看并继续服务。' : '安排一次代驾，订单进度会显示在这里。' }}</p><RouterLink class="primary-button" :to="driver ? '/driver' : '/passenger/orders/new'">{{ driver ? '返回工作台' : '预约代驾' }} <span>↗</span></RouterLink></section>
    <div v-if="data" class="order-list">
      <RouterLink v-for="order in data.items" :key="order.id" class="order-panel order-card" :to="`${base}/orders/${order.id}`">
        <div class="order-card-top"><span class="muted">订单 #{{ order.id }}</span><span class="status-badge" :class="order.status">{{ statusNames[order.status] || order.status }}</span></div>
        <h2>{{ order.pickup.name }} <span aria-hidden="true">→</span> {{ order.dropoff.name }}</h2>
        <div class="order-card-bottom"><time>{{ order.createdAt.replace('T', ' ') }}</time><strong>¥ {{ money(order.amount) }}</strong><span>查看详情 ↗</span></div>
      </RouterLink>
    </div>
    <nav v-if="data && data.total > 0" class="pagination" aria-label="订单分页"><button class="secondary-button" :disabled="loading || page === 1" @click="turn(-1)">上一页</button><span>第 {{ data.page }} 页 / 共 {{ Math.max(1, Math.ceil(data.total / 10)) }} 页</span><button class="secondary-button" :disabled="loading || page * 10 >= data.total" @click="turn(1)">下一页</button></nav>
  </main>
</template>
