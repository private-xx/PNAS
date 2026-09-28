import { http } from './http'

export interface NodeDto {
  id: string
  parentId: string | null
  name: string
  kind: 'DIR' | 'FILE'
  sizeBytes: number
  updatedAt: string
  trashedAt: string | null
}

export interface VersionDto {
  versionNo: number
  sizeBytes: number
  mimeType: string
  manifestSha256: string
  createdAt: string
}

export async function listNodes(parentId: string | null, trash = false): Promise<NodeDto[]> {
  const params: Record<string, unknown> = {}
  if (parentId) {
    params.parentId = parentId
  }
  if (trash) {
    params.trash = true
  }
  const response = await http.get<NodeDto[]>('/nodes', { params })
  return response.data
}

export async function createDir(parentId: string | null, name: string): Promise<NodeDto> {
  const response = await http.post<NodeDto>('/nodes', { parentId, name })
  return response.data
}

export async function renameNode(id: string, name: string): Promise<NodeDto> {
  const response = await http.patch<NodeDto>(`/nodes/${id}`, { name })
  return response.data
}

export async function trashNode(id: string): Promise<void> {
  await http.delete(`/nodes/${id}`)
}

export async function restoreNode(id: string): Promise<NodeDto> {
  const response = await http.post<NodeDto>(`/nodes/${id}/restore`)
  return response.data
}

export async function listVersions(id: string): Promise<VersionDto[]> {
  const response = await http.get<VersionDto[]>(`/nodes/${id}/versions`)
  return response.data
}

/** 下载直链(鉴权走 Cookie;若将来需要一次性票据应替换为票据 URL)。 */
export function contentUrl(id: string): string {
  return `/api/v1/nodes/${id}/content`
}
