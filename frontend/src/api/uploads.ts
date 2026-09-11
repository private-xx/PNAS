import { http } from './http'

/** 与服务端 Global Constraints 一致:固定 4 MiB 分块。 */
export const CHUNK_SIZE = 4 * 1024 * 1024

export async function sha256Hex(blob: Blob): Promise<string> {
  const buffer = await blob.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', buffer)
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

export async function startUpload(destParentId: string, file: File): Promise<string> {
  const response = await http.post<{ uploadId: string }>('/uploads', {
    destParentId,
    filename: file.name,
    size: file.size,
  })
  return response.data.uploadId
}

export async function putChunk(uploadId: string, seq: number, blob: Blob): Promise<void> {
  const sha = await sha256Hex(blob)
  await http.put(`/uploads/${uploadId}/chunks/${seq}`, blob, {
    headers: { 'X-Sha256': sha, 'Content-Type': 'application/octet-stream' },
  })
}

export interface CompletedUpload {
  nodeId: string
  versionNo: number
  size: number
  dedup: boolean
}

export async function completeUpload(uploadId: string): Promise<CompletedUpload> {
  const response = await http.post<CompletedUpload>(`/uploads/${uploadId}/complete`)
  return response.data
}

export async function cancelUpload(uploadId: string): Promise<void> {
  await http.post(`/uploads/${uploadId}/cancel`)
}
