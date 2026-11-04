import { request } from './api'

export type OrderStatus = 'WAITING' | 'ACCEPTED' | 'IN_SERVICE' | 'FINISHED' | 'PAID' | 'CANCELLED'
export interface Place { name: string; lng: number; lat: number }
export interface CreateOrder { requestId: string; pickup: Place; dropoff: Place }
export interface Order {
  id: number; requestId: string | null; passengerId: number | null; driverId: number | null
  pickup: Place; dropoff: Place; amount: number; status: OrderStatus; createdAt: string; updatedAt: string
}
export interface OrderPage { page: number; size: number; total: number; items: Order[] }
export const statusNames: Record<OrderStatus, string> = {
  WAITING: '等待接单', ACCEPTED: '司机已接单', IN_SERVICE: '服务中', FINISHED: '待支付', PAID: '已支付', CANCELLED: '已取消',
}
export const terminal = (status: OrderStatus) => status === 'PAID' || status === 'CANCELLED'
export const money = (value: number) => Number(value).toFixed(2)
export const orderApi = {
  create: (body: CreateOrder) => request<Order>('/api/orders', { method: 'POST', body }),
  mine: (page: number, signal?: AbortSignal) => request<OrderPage>(`/api/orders/mine?page=${page}&size=10`, { signal }),
  detail: (id: string, signal?: AbortSignal) => request<Order>(`/api/orders/${id}`, { signal }),
  action: (id: string, action: 'cancel' | 'pay-demo' | 'accept' | 'start' | 'finish') => request<Order>(`/api/orders/${id}/${action}`, { method: 'POST' }),
}
