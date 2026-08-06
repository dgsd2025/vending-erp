import request from '@/utils/request'
import { currentUserName } from '@/api/basedata'

/**
 * 任务日历 API(M2-6):今日任务(懒生成)/本周视图/手动补标/转派/任务定义/人员角色/员工详情。
 */

export interface TaskInstance {
  id: number
  taskId: number
  taskKey: string
  taskName: string
  taskDate: string
  assigneeRole?: string | null
  assigneeUserId?: number | null
  assigneeUserName?: string | null
  instanceStatus: '待办' | '已完成' | '逾期'
  checkType?: string | null
  doneType?: '系统校验' | '手动补标' | null
  doneTime?: string | null
  doneBy?: string | null
  doneNote?: string | null
  transferCount: number
}

export interface RoleColumn {
  roleCode: string
  title: string
  instances: TaskInstance[]
}

export interface TodayViewResp {
  date: string
  singleUserMode: boolean
  columns: RoleColumn[]
  instances: TaskInstance[]
  overdue: TaskInstance[]
  todoCount: number
  doneCount: number
}

export interface WeekDay {
  date: string
  future: boolean
  instances: TaskInstance[]
  preview: string[]
}

export interface RoutineTask {
  id: number
  taskKey: string
  taskName: string
  cycleRule?: string
  cycleType: string
  cycleValue: number
  anchorDate?: string | null
  assigneeRole?: string | null
  assigneeUserId?: number | null
  assigneeUserName?: string | null
  checkType?: string | null
  autoCheckRule?: string | null
  taskEnabled: number
}

export interface UserRole {
  id?: number
  userId?: number
  userName: string
  roleCode: string
  status?: number
  createTime?: string
}

export interface StaffOverviewResp {
  userName: string
  roles: string[]
  active: boolean
  taskTotal30d: number
  taskDone30d: number
  taskAutoDone30d: number
  taskManualDone30d: number
  taskOverdue30d: number
  completionRate: number | null
  todayTodo: number
  opLogStats: { targetType: string; cnt: number }[]
  opLogTotal: number
}

export interface OpLog {
  id: number
  userId: number
  userName?: string | null
  action: string
  targetType: string
  targetId?: number | null
  beforeJson?: string | null
  afterJson?: string | null
  opTime: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

export interface TaskDefReq {
  taskName: string
  cycleType: string
  cycleValue?: number
  assigneeRole?: string | null
  assigneeUserId?: number | null
  assigneeUserName?: string | null
  checkType?: string | null
  enabled?: boolean
}

const opHeaders = () => ({ headers: { 'X-User-Name': encodeURIComponent(currentUserName()) } })

export const ROLE_LABELS: Record<string, string> = {
  BOSS: '👑 老板',
  FINANCE: '💼 财务',
  REPLENISH: '🚚 补货员',
  CLERK: '✏️ 录单员',
}

export const CHECK_LABELS: Record<string, string> = {
  IMPORT_BATCH: '当日真收到导入文件',
  REPLENISH: '当日有出库上架单/建议全处理',
  STOCKTAKE: '当月有已完成盘点单',
}

export const taskApi = {
  today(date?: string): Promise<TodayViewResp> {
    return request.get('/v1/task/today', { params: { date } })
  },
  week(start?: string): Promise<WeekDay[]> {
    return request.get('/v1/task/week', { params: { start } })
  },
  manualComplete(id: number, note: string): Promise<TaskInstance> {
    return request.post(`/v1/task/instances/${id}/complete`, { note }, opHeaders())
  },
  transfer(id: number, toUserName: string, reason: string): Promise<TaskInstance> {
    return request.post(`/v1/task/instances/${id}/transfer`, { toUserName, reason }, opHeaders())
  },
  defs(): Promise<RoutineTask[]> {
    return request.get('/v1/task/defs')
  },
  createDef(req: TaskDefReq): Promise<RoutineTask> {
    return request.post('/v1/task/defs', req, opHeaders())
  },
  updateDef(id: number, req: TaskDefReq): Promise<RoutineTask> {
    return request.put(`/v1/task/defs/${id}`, req, opHeaders())
  },
  userRoles(): Promise<UserRole[]> {
    return request.get('/v1/task/user-roles')
  },
  createUserRole(userName: string, roleCode: string): Promise<UserRole> {
    return request.post('/v1/task/user-roles', { userName, roleCode }, opHeaders())
  },
  toggleUserRole(id: number, target: number): Promise<UserRole> {
    return request.put(`/v1/task/user-roles/${id}/status?target=${target}`, null, opHeaders())
  },
  staffOverview(name: string): Promise<StaffOverviewResp> {
    return request.get(`/v1/task/staff/${encodeURIComponent(name)}/overview`)
  },
  staffOpLogs(name: string, current = 1, size = 20): Promise<PageResult<OpLog>> {
    return request.get(`/v1/task/staff/${encodeURIComponent(name)}/op-logs`, { params: { current, size } })
  },
}
