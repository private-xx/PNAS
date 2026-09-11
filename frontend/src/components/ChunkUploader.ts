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

/** 文件指纹:同名同大小同修改时间视为同一文件的续传目标。 */
function fingerprint(file: File): string {
  return `${file.name}:${file.size}:${file.lastModified}`
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

async function putChunkWithRetry(uploadId: string, seq: number, blob: Blob): Promise<void> {
  let lastError: unknown
  for (let attempt = 1; attempt <= CHUNK_RETRIES; attempt++) {
    try {
      await uploadsApi.putChunk(uploadId, seq, blob)
      return
    } catch (error) {
      lastError = error
      if (attempt < CHUNK_RETRIES) {
        await new Promise((resolve) => setTimeout(resolve, 300 * attempt))
      }
    }
  }
  throw lastError instanceof Error ? lastError : new Error('分块上传失败')
}

/**
 * 分块上传器:并发 3、逐块 SHA-256、失败重试;
 * **刷新/中断后可续**:以文件指纹持久化 uploadId 与已传块号,重新选择同一文件即接着传。
 */
export function useUploader(destParentId: () => string | null, onCompleted: () => void) {
  const tasks: Ref<UploadTask[]> = ref([])

  async function uploadOne(task: UploadTask): Promise<void> {
    const parentId = destParentId()
    if (!parentId) {
      throw new Error('未选择目标目录')
    }
    task.status = 'uploading'

    const fp = fingerprint(task.file)
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
    for (let seq = 0; seq < total; seq++) {
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

    await uploadsApi.completeUpload(uploadId)
    const done = loadResume()
    delete done[fp]
    saveResume(done)
    task.progress = 100
    task.status = 'done'
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
          if (next.status !== 'cancelled') {
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
    for (const file of Array.from(files)) {
      tasks.value.push({
        id: uuid(),
        file,
        progress: 0,
        status: 'pending',
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

  /** 取消上传:通知服务端作废会话,并清理本地续传记录。 */
  async function cancelTask(id: string): Promise<void> {
    const task = tasks.value.find((t) => t.id === id)
    if (!task || task.status === 'done') {
      return
    }
    if (task.uploadId) {
      try {
        await uploadsApi.cancelUpload(task.uploadId)
      } catch {
        /* 会话可能已失效,忽略 */
      }
    }
    const map = loadResume()
    delete map[fingerprint(task.file)]
    saveResume(map)
    task.status = 'cancelled'
    task.message = '已取消'
  }

  /** 清理已结束的任务(避免队列无限增长)。 */
  function clearFinished(): void {
    tasks.value = tasks.value.filter((t) => t.status !== 'done' && t.status !== 'cancelled')
  }

  return { tasks, enqueue, retryTask, cancelTask, clearFinished }
}
