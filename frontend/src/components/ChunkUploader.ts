import { ref, type Ref } from 'vue'
import * as uploadsApi from '@/api/uploads'
import { errorMessage } from '@/api/http'

export type UploadStatus = 'pending' | 'uploading' | 'done' | 'error'

export interface UploadTask {
  id: string
  file: File
  progress: number
  status: UploadStatus
  message?: string
}

const PROGRESS_KEY = 'pnas-upload-progress'
const CONCURRENCY = 3
const CHUNK_RETRIES = 3

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

/** 记录 每会话已成功的分块序号,用于刷新/网络中断后的断点续传。 */
function loadProgress(): Record<string, number[]> {
  try {
    return JSON.parse(localStorage.getItem(PROGRESS_KEY) ?? '{}') as Record<string, number[]>
  } catch {
    return {}
  }
}

function saveProgress(map: Record<string, number[]>): void {
  localStorage.setItem(PROGRESS_KEY, JSON.stringify(map))
}

/**
 * 分块上传器:并发 3、逐块校验哈希、断点续传(本地记录已完成块)、完成后回调刷新列表。
 */
export function useUploader(destParentId: () => string | null, onCompleted: () => void) {
  const tasks: Ref<UploadTask[]> = ref([])

  async function uploadOne(task: UploadTask): Promise<void> {
    const parentId = destParentId()
    if (!parentId) {
      throw new Error('未选择目标目录')
    }
    task.status = 'uploading'
    const uploadId = await uploadsApi.startUpload(parentId, task.file)
    const total = Math.max(1, Math.ceil(task.file.size / uploadsApi.CHUNK_SIZE))
    const progressMap = loadProgress()
    const sent = new Set<number>(progressMap[uploadId] ?? [])

    for (let seq = 0; seq < total; seq++) {
      if (!sent.has(seq)) {
        const start = seq * uploadsApi.CHUNK_SIZE
        const blob = task.file.slice(start, Math.min(task.file.size, start + uploadsApi.CHUNK_SIZE))
        await putChunkWithRetry(uploadId, seq, blob)
        sent.add(seq)
        progressMap[uploadId] = Array.from(sent)
        saveProgress(progressMap)
      }
      task.progress = Math.round(((seq + 1) / total) * 100)
    }

    await uploadsApi.completeUpload(uploadId)
    delete progressMap[uploadId]
    saveProgress(progressMap)
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
          next.status = 'error'
          next.message = errorMessage(error, '上传失败')
        }
        next = queue.shift()
      }
    })
    await Promise.all(workers)
  }

  async function enqueue(files: FileList | File[]): Promise<void> {
    for (const file of Array.from(files)) {
      tasks.value.push({
        id: crypto.randomUUID(),
        file,
        progress: 0,
        status: 'pending',
      })
    }
    await drain()
  }

  /** 重试失败的任务(复用同一 task 与已记录的分块进度)。 */
  async function retryTask(id: string): Promise<void> {
    const task = tasks.value.find((t) => t.id === id)
    if (!task || task.status !== 'error') {
      return
    }
    task.status = 'pending'
    task.message = undefined
    await drain()
  }

  /** 清理已完成的任务(避免队列无限增长)。 */
  function clearFinished(): void {
    tasks.value = tasks.value.filter((t) => t.status !== 'done')
  }

  return { tasks, enqueue, retryTask, clearFinished }
}
