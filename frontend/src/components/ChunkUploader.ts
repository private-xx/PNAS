import { ref, type Ref } from 'vue'
import * as uploadsApi from '@/api/uploads'
import { errorMessage } from '@/api/http'
import { uuid } from '@/api/sha256'

export type UploadStatus = 'pending' | 'uploading' | 'done' | 'error' | 'cancelled'

export interface UploadTask {
  id: string
  file: File
  progress: number
  status: UploadStatus
  /** 目标目录(入队时冻结,避免切换到别的目录后写错位置) */
  parentId: string
  /** 目标目录 + 文件元信息:续传记录键与并发去重键 */
  fingerprint: string
  uploadId?: string
  message?: string
}

const RESUME_KEY = 'pnas-upload-resume'
const CONCURRENCY = 3
const CHUNK_RETRIES = 3

interface ResumeEntry {
  uploadId: string
  sent: number[]
}

function fingerprint(file: File, parentId: string): string {
  return `${parentId}:${file.name}:${file.size}:${file.lastModified}`
}

function loadResume(): Record<string, ResumeEntry> {
  try {
    return JSON.parse(localStorage.getItem(RESUME_KEY) ?? '{}') as Record<string, ResumeEntry>
  } catch {
    return {}
  }
}

function saveResume(map: Record<string, ResumeEntry>): void {
  localStorage.setItem(RESUME_KEY, JSON.stringify(map))
}

function dropResume(fp: string): void {
  const map = loadResume()
  delete map[fp]
  saveResume(map)
}

/** 经函数边界读取状态,避免 TS 把 status 收窄成字面量后误判"不可能相等"。 */
function isCancelled(task: UploadTask): boolean {
  return task.status === 'cancelled'
}

/** 会话语义已终结(不存在/已取消/非 OPEN):400/404/410。**不含 409** —— 409 是并发冲突,应报错而非静默重传。 */
function isStaleSession(error: unknown): boolean {
  const status = (error as { response?: { status?: number } })?.response?.status
  return status === 400 || status === 404 || status === 410
}

async function putChunkWithRetry(uploadId: string, seq: number, blob: Blob): Promise<void> {
  let lastError: unknown
  for (let attempt = 1; attempt <= CHUNK_RETRIES; attempt++) {
    try {
      await uploadsApi.putChunk(uploadId, seq, blob)
      return
    } catch (error) {
      if (isStaleSession(error)) {
        throw error // 会话失效无需重试,交给上层处理
      }
      lastError = error
      if (attempt < CHUNK_RETRIES) {
        await new Promise((resolve) => setTimeout(resolve, 300 * attempt))
      }
    }
  }
  throw lastError instanceof Error ? lastError : new Error('分块上传失败')
}

/**
 * 分块上传器:并发 3、逐块 SHA-256、失败重试;以"目标目录 + 文件指纹"持久化进度,**刷新/中断后可续**;
 * 取消会在下一个分块边界停止且**不会被任何恢复路径复活**。
 */
export function useUploader(destParentId: () => string | null, onCompleted: () => void) {
  const tasks: Ref<UploadTask[]> = ref([])

  function finishTask(task: UploadTask): void {
    dropResume(task.fingerprint)
    task.progress = 100
    task.status = 'done'
  }

  async function uploadOne(task: UploadTask, allowSessionReset = true): Promise<void> {
    if (isCancelled(task)) {
      return
    }
    const parentId = task.parentId
    if (!parentId) {
      throw new Error('未选择目标目录')
    }
    task.status = 'uploading'

    const fp = task.fingerprint
    const resume = loadResume()
    let uploadId = task.uploadId ?? resume[fp]?.uploadId
    let sent = new Set<number>(resume[fp]?.sent ?? [])
    if (!uploadId) {
      uploadId = await uploadsApi.startUpload(parentId, task.file)
      sent = new Set<number>()
      resume[fp] = { uploadId, sent: [] }
      saveResume(resume)
    }
    task.uploadId = uploadId

    const total = Math.max(1, Math.ceil(task.file.size / uploadsApi.CHUNK_SIZE))
    try {
      for (let seq = 0; seq < total; seq++) {
        if (isCancelled(task)) {
          return
        }
        if (!sent.has(seq)) {
          const start = seq * uploadsApi.CHUNK_SIZE
          const blob = task.file.slice(start, Math.min(task.file.size, start + uploadsApi.CHUNK_SIZE))
          await putChunkWithRetry(uploadId, seq, blob)
          sent.add(seq)
          const current = loadResume()
          current[fp] = { uploadId, sent: Array.from(sent) }
          saveResume(current)
        }
        task.progress = Math.round(((seq + 1) / total) * 100)
      }
      if (isCancelled(task)) {
        return
      }
      await uploadsApi.completeUpload(uploadId)
    } catch (error) {
      if (isCancelled(task)) {
        return // 取消导致的失败不得触发任何恢复逻辑
      }
      if (allowSessionReset && isStaleSession(error)) {
        // 先探测:服务端可能已完成(崩溃/响应丢失),complete 幂等返回既有结果
        try {
          await uploadsApi.completeUpload(uploadId)
          if (isCancelled(task)) {
            return // 完成响应回来前用户已取消:不得翻成 done
          }
          finishTask(task)
          onCompleted()
          return
        } catch {
          dropResume(fp)
          task.uploadId = undefined
          return uploadOne(task, false) // 确已失效:仅重建一次
        }
      }
      throw error
    }

    if (isCancelled(task)) {
      return // 完成请求期间被取消:不标记完成、不触发刷新
    }
    finishTask(task)
    onCompleted()
  }

  async function drain(): Promise<void> {
    const queue = tasks.value.filter((t) => t.status === 'pending')
    const workers = Array.from({ length: Math.min(CONCURRENCY, queue.length) }, async () => {
      let next = queue.shift()
      while (next) {
        try {
          await uploadOne(next)
        } catch (error) {
          if (!isCancelled(next)) {
            next.status = 'error'
            next.message = errorMessage(error, '上传失败')
          }
        }
        next = queue.shift()
      }
    })
    await Promise.all(workers)
  }

  async function enqueue(files: FileList | File[]): Promise<void> {
    const parentId = destParentId()
    if (!parentId) {
      return
    }
    for (const file of Array.from(files)) {
      const fp = fingerprint(file, parentId)
      const active = tasks.value.some(
        (t) =>
          t.fingerprint === fp &&
          (t.status === 'pending' || t.status === 'uploading' || t.status === 'error'),
      )
      if (active) {
        continue // 同一目标目录的同一文件已在队列(含待重试)时不重复入队
      }
      tasks.value.push({
        id: uuid(),
        file,
        progress: 0,
        status: 'pending',
        parentId,
        fingerprint: fp,
      })
    }
    await drain()
  }

  /** 重试失败的任务(复用已记录的 uploadId 与分块进度)。 */
  async function retryTask(id: string): Promise<void> {
    const task = tasks.value.find((t) => t.id === id)
    if (!task || task.status !== 'error') {
      return
    }
    task.status = 'pending'
    task.message = undefined
    await drain()
  }

  /** 取消上传:先置取消态(在飞上传会在下一分块边界停止且不被复活),再作废服务端会话。 */
  async function cancelTask(id: string): Promise<void> {
    const task = tasks.value.find((t) => t.id === id)
    if (!task || task.status === 'done') {
      return
    }
    task.status = 'cancelled'
    task.message = '已取消'
    if (task.uploadId) {
      try {
        await uploadsApi.cancelUpload(task.uploadId)
      } catch {
        /* 会话可能已完成/已失效,忽略 */
      }
    }
    dropResume(task.fingerprint)
  }

  /** 清理已结束的任务(避免队列无限增长)。 */
  function clearFinished(): void {
    tasks.value = tasks.value.filter((t) => t.status !== 'done' && t.status !== 'cancelled')
  }

  return { tasks, enqueue, retryTask, cancelTask, clearFinished }
}
