import { http } from './http'

/** 任务状态(与后端 Job 状态机一致:QUEUED → RUNNING → SUCCESS | FAILED → DEAD)。 */
export type JobState = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'DEAD'

/** 任务队列条目(M2 后端 Job 实体 JSON)。 */
export interface JobDto {
  id: string
  type: string
  payload: Record<string, unknown>
  state: JobState
  attempt: number
  maxAttempts: number
  priority: number
  nextRunAt: string
  startedAt: string | null
  finishedAt: string | null
  error: string | null
  result: Record<string, unknown> | null
}

/** 列出任务;state 为空时返回全部。 */
export async function listJobs(state?: string): Promise<JobDto[]> {
  const response = await http.get<JobDto[]>('/jobs', {
    params: state ? { state } : {},
  })
  return response.data
}

/** 重试 FAILED/DEAD 任务。 */
export async function retryJob(id: string): Promise<JobDto> {
  const response = await http.post<JobDto>(`/jobs/${id}/retry`)
  return response.data
}

/** 取消 QUEUED/RUNNING 任务。 */
export async function cancelJob(id: string): Promise<JobDto> {
  const response = await http.post<JobDto>(`/jobs/${id}/cancel`)
  return response.data
}
