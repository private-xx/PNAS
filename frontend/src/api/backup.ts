import { http } from './http'

/** 设备平台(与后端 DevicePlatform 一致)。 */
export type DevicePlatform = 'MAC' | 'WINDOWS' | 'OTHER'

/** 备份设备(M4a 后端 Device 实体 JSON)。 */
export interface DeviceDto {
  id: string
  name: string
  platform: DevicePlatform
  createdAt: string
  lastSeenAt: string | null
  revoked: boolean
}

/** 运行模式(与后端 RunMode 一致)。 */
export type RunMode = 'MANUAL' | 'SCHEDULED' | 'REALTIME'

/** 运行状态(与后端 RunStatus 一致)。 */
export type RunStatus = 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED'

/** 备份运行记录(M4a 后端 BackupRun 实体 JSON)。 */
export interface RunDto {
  id: string
  deviceId: string
  mode: RunMode
  status: RunStatus
  startedAt: string
  finishedAt: string | null
  filesSeen: number
  filesUploaded: number
  filesSkipped: number
  filesFailed: number
  bytesUploaded: number
  error: string | null
}

/** 新建设备的响应:令牌只在创建时返回一次。 */
export interface CreatedDeviceDto {
  device: DeviceDto
  token: string
}

/** 列出当前用户的备份设备。 */
export async function listDevices(): Promise<DeviceDto[]> {
  return (await http.get<DeviceDto[]>('/devices')).data
}

/** 新建设备并领取一次性令牌。 */
export async function createDevice(name: string, platform: DevicePlatform): Promise<CreatedDeviceDto> {
  return (await http.post<CreatedDeviceDto>('/devices', { name, platform })).data
}

/** 吊销设备(DELETE,后端返回 204)。 */
export async function revokeDevice(id: string): Promise<void> {
  await http.delete(`/devices/${id}`)
}

/** 列出最近的备份运行记录。 */
export async function listRuns(limit = 50): Promise<RunDto[]> {
  return (await http.get<RunDto[]>('/backup/runs', { params: { limit } })).data
}
