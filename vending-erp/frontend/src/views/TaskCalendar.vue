<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  taskApi,
  ROLE_LABELS,
  CHECK_LABELS,
  type TodayViewResp,
  type WeekDay,
  type TaskInstance,
  type RoutineTask,
  type UserRole,
} from '@/api/task'
import { currentUserName } from '@/api/basedata'

/**
 * 任务日历(M2-6,对照 mockup p12 任务日历卡):
 * 固定任务引擎——每天早上它决定今日工作台显示什么。
 * 完成有系统校验(✅自动),不是打勾就算;手动补标只有🟡黄标;逾期未完成→🔴次日红灯。
 * 任务绑角色、角色绑人:单人模式列合并,加人自动拆列;任何任务可转派(op_log 留痕)。
 */
const router = useRouter()

const loading = ref(false)
const today = ref<TodayViewResp | null>(null)
const week = ref<WeekDay[]>([])
const defs = ref<RoutineTask[]>([])
const roles = ref<UserRole[]>([])

async function load() {
  loading.value = true
  try {
    const [t, w, d, r] = await Promise.all([
      taskApi.today(),
      taskApi.week(),
      taskApi.defs(),
      taskApi.userRoles(),
    ])
    today.value = t
    week.value = w
    defs.value = d
    roles.value = r
  } finally {
    loading.value = false
  }
}
onMounted(load)

const activeRoles = computed(() => roles.value.filter((r) => r.status === 1))

// ---------- 状态 chip ----------
function statusChip(i: TaskInstance): { cls: string; text: string } {
  if (i.instanceStatus === '已完成') {
    return i.doneType === '系统校验'
      ? { cls: 'c-green', text: '✅ 系统校验通过' }
      : { cls: 'c-amber', text: '🟡 手动补标' }
  }
  if (i.instanceStatus === '逾期') return { cls: 'c-red', text: '🔴 逾期' }
  return { cls: 'c-gray', text: '⬜ 待办' }
}

// ---------- 手动补标 ----------
const completeDlg = ref(false)
const completeTarget = ref<TaskInstance | null>(null)
const completeNote = ref('')
function openComplete(i: TaskInstance) {
  completeTarget.value = i
  completeNote.value = ''
  completeDlg.value = true
}
async function doComplete() {
  if (!completeTarget.value) return
  await taskApi.manualComplete(completeTarget.value.id, completeNote.value)
  ElMessage.success('已手动补标(黄标留痕:未过系统校验)')
  completeDlg.value = false
  load()
}

// ---------- 转派 ----------
const transferDlg = ref(false)
const transferTarget = ref<TaskInstance | null>(null)
const transferTo = ref('')
const transferReason = ref('')
function openTransfer(i: TaskInstance) {
  transferTarget.value = i
  transferTo.value = ''
  transferReason.value = ''
  transferDlg.value = true
}
async function doTransfer() {
  if (!transferTarget.value || !transferTo.value) {
    ElMessage.warning('请选择转派对象')
    return
  }
  await taskApi.transfer(transferTarget.value.id, transferTo.value, transferReason.value)
  ElMessage.success(`已转派给 ${transferTo.value}(op_log 留痕)`)
  transferDlg.value = false
  load()
}
const transferCandidates = computed(() => {
  const cur = transferTarget.value?.assigneeUserName
  return [...new Set(activeRoles.value.map((r) => r.userName))].filter((n) => n !== cur)
})

// ---------- 人员角色 ----------
const newName = ref('')
const newRole = ref('CLERK')
async function addRole() {
  if (!newName.value.trim()) {
    ElMessage.warning('先填人名')
    return
  }
  await taskApi.createUserRole(newName.value.trim(), newRole.value)
  ElMessage.success('已添加角色映射')
  newName.value = ''
  load()
}
async function toggleRole(r: UserRole) {
  await taskApi.toggleUserRole(r.id!, r.status === 1 ? 0 : 1)
  load()
}

// ---------- 工具 ----------
const fmtDay = (d: string) => d.slice(5)
const weekdayName = (d: string) =>
  ['日', '一', '二', '三', '四', '五', '六'][new Date(d + 'T00:00:00').getDay()]
const isToday = (d: string) => today.value?.date === d
const roleLabel = (code?: string | null) => (code ? (ROLE_LABELS[code] ?? code) : '—')
const checkLabel = (t?: string | null) => (t ? (CHECK_LABELS[t] ?? t) : '纯手动(无系统校验)')
</script>

<template>
  <div class="ledger-page" v-loading="loading">
    <div class="ledger-crumb">园区小卖 ERP / 系统 / 任务日历</div>
    <div class="ledger-title">
      <h2>任务日历</h2>
      <span class="sub">固定任务引擎 —— 每天早上它决定「今日工作台」显示什么</span>
    </div>

    <!-- ⚡任务来源说明(七律#2:领着人干活) -->
    <div class="ledger-card task-src">
      ⚡ <b>任务从哪来:</b>固定任务引擎按周期自动生成(导数据每日 / 补货巡检每3天 / 盘点每月1日…),按角色派给当班的人;
      <b>怎么算完成:</b>系统校验自动打勾 ✅(导数据=真收到文件,不是打勾就算);拿不到校验只能「手动补标」🟡 留痕;
      逾期未完成 → 次日 🔴 红灯。任何任务可 ↪ 转派(谁转给谁、为什么,永远可查)。
    </div>

    <!-- 逾期红灯 -->
    <div v-if="today?.overdue?.length" class="overdue-strip">
      🔴 <b>{{ today.overdue.length }} 个任务逾期未完成</b>
      <span v-for="o in today.overdue.slice(0, 3)" :key="o.id" class="ov-item">
        {{ fmtDay(o.taskDate) }} {{ o.taskName }}({{ o.assigneeUserName || roleLabel(o.assigneeRole) }})
        <a @click="openComplete(o)">补标</a>
      </span>
    </div>

    <!-- 今日任务:按角色分列 / 单人合并 -->
    <div class="ledger-card">
      <h3>
        📋 今日任务 <span class="chip c-gray">{{ today?.date }}</span>
        <span v-if="today?.singleUserMode" class="chip c-blue">单人模式 · 列已合并(加人自动拆列)</span>
        <span class="hint" style="margin-left: auto">
          待办 {{ today?.todoCount ?? 0 }} · 已完成 {{ today?.doneCount ?? 0 }}
        </span>
      </h3>
      <div class="col-grid" :style="{ gridTemplateColumns: `repeat(${Math.max(today?.columns?.length ?? 1, 1)}, 1fr)` }">
        <div v-for="col in today?.columns ?? []" :key="col.roleCode" class="role-col">
          <div class="col-head">{{ col.title }}</div>
          <div v-for="i in col.instances" :key="i.id" class="task-item" :class="{ done: i.instanceStatus === '已完成' }">
            <div class="t-name">{{ i.taskName }}</div>
            <div class="t-meta">
              <span class="chip" :class="statusChip(i).cls">{{ statusChip(i).text }}</span>
              <span class="mini">校验:{{ checkLabel(i.checkType) }}</span>
            </div>
            <div class="t-meta">
              <a v-if="i.assigneeUserName" class="plink" @click="router.push(`/staff/${i.assigneeUserName}`)">
                {{ i.assigneeUserName }} ↗
              </a>
              <span v-else class="mini">{{ roleLabel(i.assigneeRole) }} · 未配人</span>
              <span v-if="i.transferCount" class="mini">↪ 转派 {{ i.transferCount }} 次</span>
              <span style="margin-left: auto; display: flex; gap: 8px">
                <a v-if="i.instanceStatus !== '已完成'" class="op" @click="openComplete(i)">补标</a>
                <a v-if="i.instanceStatus !== '已完成'" class="op" @click="openTransfer(i)">↪ 转派</a>
              </span>
            </div>
            <div v-if="i.doneType === '手动补标'" class="manual-note">
              🟡 手动确认,未过系统校验 · {{ i.doneBy }}:{{ i.doneNote }}
            </div>
          </div>
          <el-empty v-if="!col.instances.length" description="今日无任务" :image-size="40" />
        </div>
      </div>
      <el-empty v-if="!today?.columns?.length" description="没有到期任务(检查任务定义是否启用)" :image-size="60" />
    </div>

    <!-- 本周 7 列格 -->
    <div class="ledger-card">
      <h3>🗓 本周视图 <span class="hint">≤今日为真实实例;未来日是预告,不落库</span></h3>
      <div class="week-grid">
        <div v-for="d in week" :key="d.date" class="wday" :class="{ today: isToday(d.date), future: d.future }">
          <div class="w-head">
            周{{ weekdayName(d.date) }} <span class="num">{{ fmtDay(d.date) }}</span>
            <span v-if="isToday(d.date)" class="chip c-green" style="padding: 0 6px">今天</span>
          </div>
          <template v-if="!d.future">
            <div v-for="i in d.instances" :key="i.id" class="w-item" :title="i.taskName">
              <span :class="['dot', statusChip(i).cls]"></span>{{ i.taskName }}
            </div>
            <div v-if="!d.instances.length" class="mini" style="padding: 4px">—</div>
          </template>
          <template v-else>
            <div v-for="(p, pi) in d.preview" :key="pi" class="w-item preview">◌ {{ p }}</div>
            <div v-if="!d.preview.length" class="mini" style="padding: 4px">—</div>
          </template>
        </div>
      </div>
    </div>

    <div class="two-col">
      <!-- 任务定义 -->
      <div class="ledger-card">
        <h3>⚙️ 任务定义(固定任务引擎) <span class="hint">内置种子 + 自定义;停用不再生成,历史保留</span></h3>
        <table class="ttab">
          <tr><th>频率</th><th>任务</th><th>责任角色</th><th>系统校验</th><th>状态</th></tr>
          <tr v-for="d in defs" :key="d.id">
            <td><b>{{ d.cycleRule || d.cycleType }}</b></td>
            <td>{{ d.taskName }}</td>
            <td>{{ roleLabel(d.assigneeRole) }}<span v-if="d.assigneeUserName" class="mini"> · 指定 {{ d.assigneeUserName }}</span></td>
            <td class="mini">{{ d.checkType ? (CHECK_LABELS[d.checkType] ?? d.autoCheckRule) : '纯手动' }}</td>
            <td><span class="chip" :class="d.taskEnabled === 1 ? 'c-green' : 'c-gray'">{{ d.taskEnabled === 1 ? '启用' : '停用' }}</span></td>
          </tr>
        </table>
        <p class="mini" style="margin-top: 8px">
          💡 负责人按「角色」配,不按「人」配:任务生成时自动派给当时担任该角色的人;人员变动只改右侧映射,任务日历不用动。
        </p>
      </div>

      <!-- 人员与角色 -->
      <div class="ledger-card">
        <h3>👥 人员与角色 <span class="hint">SSO 前手工维护 · 人名可点进员工详情</span></h3>
        <div style="display: flex; gap: 8px; margin: 10px 0">
          <el-input v-model="newName" placeholder="人名(如 小邱)" style="width: 140px" size="small" />
          <el-select v-model="newRole" size="small" style="width: 130px">
            <el-option v-for="(label, code) in ROLE_LABELS" :key="code" :label="label" :value="code" />
          </el-select>
          <el-button size="small" type="primary" @click="addRole">＋ 添加</el-button>
        </div>
        <table class="ttab">
          <tr><th>人员</th><th>角色</th><th>状态</th><th>操作</th></tr>
          <tr v-for="r in roles" :key="r.id">
            <td><a class="plink" @click="router.push(`/staff/${r.userName}`)">{{ r.userName }} ↗</a></td>
            <td>{{ roleLabel(r.roleCode) }}</td>
            <td><span class="chip" :class="r.status === 1 ? 'c-green' : 'c-gray'">{{ r.status === 1 ? '在岗' : '停用' }}</span></td>
            <td><a class="op" @click="toggleRole(r)">{{ r.status === 1 ? '停用' : '启用' }}</a></td>
          </tr>
        </table>
        <p v-if="!roles.length" class="mini">还没配人:所有任务只挂角色。配上第一个人=单人模式(列合并);第二个人加入自动拆列。</p>
      </div>
    </div>

    <p class="ledger-foot-note">
      — 任务绑角色、角色绑人;完成有系统校验,不是打勾就算;逾期升级,转派留痕 —
    </p>

    <!-- 手动补标弹窗 -->
    <el-dialog v-model="completeDlg" title="🟡 手动补标(未过系统校验)" width="440px">
      <p class="mini" style="margin-top: 0">
        「{{ completeTarget?.taskName }}」的系统校验还没通过:{{ checkLabel(completeTarget?.checkType) }}。<br />
        手动补标会留痕「手动确认,未过系统校验」,拿的是🟡黄标,不是✅绿勾。
      </p>
      <el-input v-model="completeNote" type="textarea" :rows="2" placeholder="补标原因(如:今天顺路巡过了,后台没导记录)" />
      <template #footer>
        <el-button @click="completeDlg = false">取消</el-button>
        <el-button type="warning" @click="doComplete">确认补标(留痕)</el-button>
      </template>
    </el-dialog>

    <!-- 转派弹窗 -->
    <el-dialog v-model="transferDlg" title="↪ 转派任务" width="440px">
      <p class="mini" style="margin-top: 0">
        「{{ transferTarget?.taskName }}」当前责任人:<b>{{ transferTarget?.assigneeUserName || '未配人' }}</b>。
        转派会记 op_log:谁转给谁、几点、为什么,永远可查。
      </p>
      <el-select v-model="transferTo" placeholder="转派给(在岗人员)" style="width: 100%; margin-bottom: 8px" filterable allow-create>
        <el-option v-for="n in transferCandidates" :key="n" :label="n" :value="n" />
      </el-select>
      <el-input v-model="transferReason" placeholder="转派原因(如:小邱请假)" />
      <template #footer>
        <el-button @click="transferDlg = false">取消</el-button>
        <el-button type="primary" @click="doTransfer">确认转派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.task-src {
  font-size: 12.5px;
  color: var(--ink2);
  line-height: 1.8;
  background: #f4f7f2;
  border-color: #d8e3d4;
  padding: 12px 18px;
}
.overdue-strip {
  background: var(--red-soft);
  border: 1px solid #eac6bf;
  color: #8c2b20;
  border-radius: 10px;
  padding: 10px 16px;
  font-size: 13px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.overdue-strip .ov-item {
  font-size: 12px;
}
.overdue-strip a {
  color: var(--red);
  text-decoration: underline;
  cursor: pointer;
  margin-left: 4px;
}
.col-grid {
  display: grid;
  gap: 12px;
  margin-top: 10px;
}
.role-col {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--card);
  overflow: hidden;
}
.col-head {
  background: #f2efe6;
  padding: 8px 12px;
  font-size: 12.5px;
  font-weight: 700;
  border-bottom: 1px solid var(--line);
}
.task-item {
  padding: 10px 12px;
  border-bottom: 1px dashed var(--line);
}
.task-item.done {
  background: #fbfaf5;
}
.task-item:last-child {
  border-bottom: none;
}
.t-name {
  font-size: 13.5px;
  font-weight: 600;
  margin-bottom: 6px;
}
.task-item.done .t-name {
  color: var(--ink2);
}
.t-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-top: 4px;
  flex-wrap: wrap;
}
.t-meta .op {
  color: var(--green);
  cursor: pointer;
  text-decoration: underline;
}
.manual-note {
  font-size: 11.5px;
  color: #8a5a1d;
  background: var(--amber-soft);
  border-radius: 6px;
  padding: 4px 8px;
  margin-top: 6px;
}
.week-grid {
  display: grid;
  /* minmax(0,1fr):任务名 nowrap 会把列撑破容器(1280 下整页横向溢出),压回等分让文本省略 */
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 8px;
  margin-top: 10px;
}
.wday {
  border: 1px solid var(--line);
  border-radius: 8px;
  min-height: 90px;
  background: var(--card);
}
.wday.today {
  border-color: var(--green);
  box-shadow: inset 0 3px 0 var(--green);
}
.wday.future {
  background: #faf8f1;
}
.w-head {
  font-size: 11.5px;
  color: var(--ink2);
  padding: 6px 8px;
  border-bottom: 1px dashed var(--line);
  display: flex;
  gap: 6px;
  align-items: center;
}
.w-item {
  font-size: 11.5px;
  padding: 4px 8px;
  display: flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.w-item.preview {
  color: #a89f8a;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  display: inline-block;
}
.dot.c-green { background: var(--green); }
.dot.c-amber { background: var(--amber); }
.dot.c-red { background: var(--red); }
.dot.c-gray { background: #c9c2b1; }
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.ttab {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
  margin-top: 8px;
}
.ttab th {
  font-size: 12px;
  color: var(--ink2);
  text-align: left;
  padding: 7px 8px;
  border-bottom: 2px solid var(--line2);
  background: #faf7ef;
}
.ttab td {
  padding: 8px;
  border-bottom: 1px solid var(--line);
}
.ttab .op {
  color: var(--green);
  cursor: pointer;
  text-decoration: underline;
}
.plink {
  color: var(--green);
  cursor: pointer;
  font-weight: 600;
}

/* 手机版(≤768px):周视图 2 列、双栏竖排、任务列竖排(M2-5 铁律10) */
@media (max-width: 768px) {
  .week-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .two-col {
    grid-template-columns: 1fr;
  }
  .two-col > *,
  .col-grid > * {
    min-width: 0;
  }
  .col-grid {
    grid-template-columns: 1fr !important; /* 覆盖行内 repeat(n,1fr),角色列竖排 */
  }
}
</style>
