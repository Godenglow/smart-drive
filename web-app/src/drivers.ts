import { request } from './api'
export interface Profile { userId: number; displayName: string; createdAt: string }
export interface DriverState { profile: Profile | null; online: boolean }
export interface Location { lng: number; lat: number }
export interface Online { online: boolean; updatedAt: string }
export const driverApi = {
  state: (signal?: AbortSignal) => request<DriverState>('/api/drivers/me/status', { signal }),
  profile: (displayName: string) => request<Profile>('/api/drivers/me/profile', { method: 'PUT', body: { displayName } }),
  online: (body: Location) => request<Online>('/api/drivers/me/online', { method: 'PUT', body }),
  location: (body: Location, signal?: AbortSignal) => request<Online>('/api/drivers/me/location', { method: 'PUT', body, signal }),
  offline: () => request<Online>('/api/drivers/me/offline', { method: 'PUT' }),
  notices: (signal?: AbortSignal) => request<number[]>('/api/drivers/me/notices?status=NEW&limit=20', { signal }),
}
export function validLocation(lng: string | number, lat: string | number): Location | null {
  const x = Number(lng), y = Number(lat)
  return String(lng).trim() && String(lat).trim() && Number.isFinite(x) && Number.isFinite(y) && Math.abs(x) <= 180 && Math.abs(y) <= 85.05112878 ? { lng: x, lat: y } : null
}
