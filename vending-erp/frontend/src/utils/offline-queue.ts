import { ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { saveStocktakeItems, submitStocktake } from '@/api/stocktake'
import { executeTicket } from '@/api/prekit'

/**
 * 离线回传队列(M2-5):补货员在机器旁常遇弱网/断网。
 * - 现场录入实时草稿由各页自管 localStorage(键=单据 id);
 * - 提交类请求网络失败 → 入队本模块(localStorage 持久化)+ 顶部黄条提示;
 * - 网络恢复(online 事件)/应用启动时自动按序重放,成功即清队;
 * - 重放遇业务错(非网络)= 数据冲突,弃单并明确报错,不许静默卡死队列。
 */

export interface OfflineTask {
  /** `${type}:${dedupeKey}`,同键覆盖旧任务防堆积 */
  id: string
  type: string
  /** 人话描述,黄条/报错时露出 */
  label: string
  payload: unknown
  createdAt: string
}

const QUEUE_KEY = 'vend_offline_queue'

function loadQueue(): OfflineTask[] {
  try {
    const arr = JSON.parse(localStorage.getItem(QUEUE_KEY) || '[]')
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

/** 待回传任务(响应式,顶部黄条直接读) */
export const pendingTasks = ref<OfflineTask[]>(loadQueue())
/** 浏览器离线态(navigator.onLine + online/offline 事件) */
export const isOffline = ref(typeof navigator !== 'undefined' ? !navigator.onLine : false)
export const replaying = ref(false)

function persist() {
  localStorage.setItem(QUEUE_KEY, JSON.stringify(pendingTasks.value))
}

// ---------- 重放器(集中注册,任意页面启动都能重放全量队列) ----------

type Handler = (payload: any) => Promise<void>

const handlers: Record<string, Handler> = {
  /** 盘点差异保存:整包替换,天然幂等 */
  'stocktake-save': async (p: { id: number; rows: Parameters<typeof saveStocktakeItems>[1] }) => {
    await saveStocktakeItems(p.id, p.rows)
  },
  /** 盘点提交:先补一次保存再提交(带走离线期间最后一版差异) */
  'stocktake-submit': async (p: { id: number; rows: Parameters<typeof saveStocktakeItems>[1] }) => {
    await saveStocktakeItems(p.id, p.rows)
    await submitStocktake(p.id)
  },
  /** 配货单标记已执行 */
  'prekit-execute': async (p: { id: number }) => {
    await executeTicket(p.id)
  },
}

// ---------- 重放完成通知(页面刷新列表用) ----------

type ReplayListener = (task: OfflineTask) => void
const listeners = new Set<ReplayListener>()

/** 订阅"某任务重放成功";返回退订函数(onUnmounted 时调) */
export function onOfflineReplayed(fn: ReplayListener): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

// ---------- 判定 + 入队 ----------

/** 网络类失败(断网/超时/DNS)才离线暂存;业务错(R.code!=200)不入队 */
export function isNetworkError(e: unknown): boolean {
  if (typeof navigator !== 'undefined' && !navigator.onLine) return true
  if (axios.isAxiosError(e)) return !e.response
  const msg = (e as Error)?.message || ''
  return /network|timeout|fetch/i.test(msg) || msg === '网络异常'
}

export function enqueueOffline(type: string, label: string, payload: unknown, dedupeKey: string) {
  const id = `${type}:${dedupeKey}`
  pendingTasks.value = pendingTasks.value.filter((t) => t.id !== id)
  pendingTasks.value.push({ id, type, label, payload, createdAt: new Date().toISOString() })
  persist()
}

/** 移除指定任务(如:提交任务已包含保存,入队提交时清掉同单的保存任务) */
export function dropOfflineTask(type: string, dedupeKey: string) {
  const id = `${type}:${dedupeKey}`
  const before = pendingTasks.value.length
  pendingTasks.value = pendingTasks.value.filter((t) => t.id !== id)
  if (pendingTasks.value.length !== before) persist()
}

// ---------- 顺序重放 ----------

/**
 * 按入队顺序重放:
 * - 网络错 → 停止保序,留队等下次 online;
 * - 业务错 → 弃该单并报错(数据已冲突,重试无意义),继续后面的。
 */
export async function replayQueue(): Promise<void> {
  if (replaying.value || !pendingTasks.value.length) return
  if (typeof navigator !== 'undefined' && !navigator.onLine) return
  replaying.value = true
  let ok = 0
  try {
    while (pendingTasks.value.length) {
      const task = pendingTasks.value[0]
      const fn = handlers[task.type]
      if (!fn) {
        // 未知类型(旧版本残留):弃掉,防止永久卡队
        pendingTasks.value.shift()
        persist()
        continue
      }
      try {
        await fn(task.payload)
        pendingTasks.value.shift()
        persist()
        ok++
        listeners.forEach((l) => l(task))
      } catch (e) {
        if (isNetworkError(e)) {
          break // 还没恢复,保序留队
        }
        // 业务错:弃单 + 明确报错,不许静默
        pendingTasks.value.shift()
        persist()
        ElMessage.error(`离线任务重传被拒:「${task.label}」——${(e as Error)?.message || '业务冲突'},请到对应单据人工核对`)
      }
    }
  } finally {
    replaying.value = false
  }
  if (ok > 0) ElMessage.success(`网络已恢复:离线暂存自动重传成功 ${ok} 条`)
}

// ---------- 全局监听 ----------

if (typeof window !== 'undefined') {
  window.addEventListener('online', () => {
    isOffline.value = false
    void replayQueue()
  })
  window.addEventListener('offline', () => {
    isOffline.value = true
  })
  // 应用启动 2s 后补一次重放(上次断网期间关了页面的场景)
  setTimeout(() => void replayQueue(), 2000)
}
