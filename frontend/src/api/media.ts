import { http } from './http'

export type MediaKind = 'IMAGE' | 'VIDEO'
export type ThumbStatus = 'PENDING' | 'READY' | 'FAILED' | 'SKIPPED'

export interface TimelineItem {
  assetId: string
  nodeId: string
  name: string
  kind: MediaKind
  takenAt: string
  width: number | null
  height: number | null
  durationMs: number | null
  thumbStatus: ThumbStatus
  sizeBytes: number
}

export interface PeriodCount {
  period: string
  count: number
}

export interface YearMonths {
  year: number
  months: PeriodCount[]
}

export interface AssetDetail {
  assetId: string
  nodeId: string
  name: string
  kind: MediaKind
  takenAt: string
  takenAtSource: 'EXIF' | 'MTIME'
  width: number | null
  height: number | null
  durationMs: number | null
  thumbStatus: ThumbStatus
  hasGps: boolean
  sizeBytes: number
}

export async function listTimeline(params: {
  cursor?: string | null
  limit?: number
  kind?: MediaKind | null
}): Promise<TimelineItem[]> {
  const query: Record<string, unknown> = { limit: params.limit ?? 60 }
  if (params.cursor) query.cursor = params.cursor
  if (params.kind) query.kind = params.kind
  const response = await http.get<TimelineItem[]>('/media/timeline', { params: query })
  return response.data
}

export async function listBuckets(year: number, kind?: MediaKind | null): Promise<PeriodCount[]> {
  const query: Record<string, unknown> = { year }
  if (kind) query.kind = kind
  const response = await http.get<PeriodCount[]>('/media/timeline/buckets', { params: query })
  return response.data
}

export async function listAlbumDates(): Promise<YearMonths[]> {
  const response = await http.get<YearMonths[]>('/media/albums/dates')
  return response.data
}

export async function getAsset(id: string): Promise<AssetDetail> {
  const response = await http.get<AssetDetail>(`/media/assets/${id}`)
  return response.data
}

export async function reindexMedia(): Promise<{ jobId: string }> {
  const response = await http.post<{ jobId: string }>('/media/reindex')
  return response.data
}

/** 缩略图直链(鉴权走 Cookie,spec D1)。 */
export function thumbUrl(assetId: string, size: 256 | 512 = 256): string {
  return `/api/v1/media/assets/${assetId}/thumb?size=${size}`
}

/** 原图/视频直链(支持 Range,供 <video> 使用)。 */
export function mediaContentUrl(assetId: string): string {
  return `/api/v1/media/assets/${assetId}/content`
}
