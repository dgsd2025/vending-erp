<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { guideApi, type SetupStatus } from '@/api/guide'
import { useTour } from '@/composables/useTour'

/**
 * 新手指引中心(帮助中心)——同事上手的总入口:
 * ① 开业向导:上手三步做成带真实进度的清单(读 setup-status,不写死);
 * ② 每日流程速查卡:今天该干嘛;
 * ③ 逐页浮层引导:带我逛一圈(useTour);
 * ④ 帮助手册:分模块图文,搜索过滤,不懂就翻这里。
 */
const router = useRouter()
const { start: startTour } = useTour()

const status = ref<SetupStatus | null>(null)
const loading = ref(true)

onMounted(async () => {
  try {
    status.value = await guideApi.setupStatus()
  } catch {
    status.value = null
  } finally {
    loading.value = false
  }
})

/** 第1步 · 建档案 5 小项(真实进度) */
const step1Items = computed(() => {
  const s = status.value
  return [
    { key: 'machine', label: '机器与货道', tip: '后台设备ID 要和 fanmaiji.top 对齐', ok: !!s && s.machineCount > 0, count: s?.machineCount ?? 0, to: '/settings' },
    { key: 'product', label: '商品档案', tip: 'SKU / 条码 / 别名', ok: !!s && s.productCount > 0, count: s?.productCount ?? 0, to: '/settings' },
    { key: 'supplier', label: '供应商', tip: '结算方式 / 账期', ok: !!s && s.supplierCount > 0, count: s?.supplierCount ?? 0, to: '/settings' },
    { key: 'account', label: '资金账户', tip: '期初余额只能设一次,别填错', ok: !!s && s.accountCount > 0, count: s?.accountCount ?? 0, to: '/settings' },
    { key: 'settle', label: '结算模式', tip: s?.settleModeSet ? `已定:${s.settleMode}` : '现在「未定 UNSET」,核实后定平台/直营', ok: !!s && s.settleModeSet, count: 0, to: '/settings' },
  ]
})
const step1Done = computed(() => step1Items.value.every((i) => i.ok))
const step1Left = computed(() => step1Items.value.filter((i) => !i.ok).length)
const step2Done = computed(() => !!status.value?.prekitDone)
const step3Done = computed(() => !!status.value?.reconciled)
const doneCount = computed(() => [step1Done.value, step2Done.value, step3Done.value].filter(Boolean).length)
const allDone = computed(() => doneCount.value === 3)

/** 手风琴:默认展开第一个未完成的大步 */
const activeStep = ref('1')
onMounted(() => {
  setTimeout(() => {
    if (step1Done.value && !step2Done.value) activeStep.value = '2'
    else if (step1Done.value && step2Done.value && !step3Done.value) activeStep.value = '3'
  }, 300)
})

const go = (to: string) => router.push(to)

/** 每日流程速查卡 */
const dailyFlow = [
  { n: '① 早上', t: '导出后台数据', d: 'fanmaiji.top 导「截至昨日」3 个文件', to: '/import' },
  { n: '②', t: '三通道导入', d: '出货明细 / 补货记录 / 商品列表 拖进来', to: '/import' },
  { n: '③', t: '核对入账', d: '看预览,确认无误入账', to: '/import' },
  { n: '④', t: '处理差异', d: '待绑定商品 / 改价 / 负库存红灯', to: '/import' },
  { n: '⑤', t: '补货 & 对账', d: '看 AI 补货、出库配货、资金对账', to: '/replenish' },
]

/** 帮助手册 */
const faqs = [
  {
    em: '📥', q: '导入中心怎么用?三个通道分别是什么?',
    a: [
      ['这是什么', '全系统的数据入口。每天把后台导出的 Excel 拖进来,系统自动记账,不用手敲数字。'],
      ['怎么做', '① 出货明细 → 销售记录;② 补货记录 → 出库上架转移单;③ 商品列表 → 别名。拖文件 → 看预览 → 确认入账。'],
      ['常见问题', '名称对不上会进「待绑定队列」,到差异处理里手动绑一次,以后系统自动认。整批可回滚,导错了不怕。'],
    ],
  },
  {
    em: '🚚', q: '出库上架 / AI 补货 / 配货核销怎么走?',
    a: [
      ['这是什么', '把仓库的货补到机器里,并跟踪补了多少、带回多少。'],
      ['怎么做', '看「AI 补货提示」的建议 → 「出库上架」生成配货单 → 去补货 → 次日补货记录导入,系统自动核销,差量算「带回率」。'],
      ['常见问题', '手工建的转移单一律先「预挂单」,等次日导入匹配冲抵;超 48 小时没匹配才转正。'],
    ],
  },
  {
    em: '🚛', q: '采购入库怎么录?为什么只有它要手工?',
    a: [
      ['这是什么', '进货到货时录的单据。这是日常唯一需要手工录的单,其余全靠导入自动生成。'],
      ['怎么做', '「采购入库」→ 新增 → 选供应商、商品、数量、单价 → 确认入库,仓库库存 +。'],
      ['常见问题', '金额和数量二选一填,系统自动折算;没现价就只填金额。'],
    ],
  },
  {
    em: '💰', q: '资金与对账、供应商往来(应付)怎么看?',
    a: [
      ['这是什么', '所有钱的流水和欠款。每一笔进出都有据可查。'],
      ['怎么做', '「资金与对账」看每笔现金流水、做钱盘对账;「供应商往来」看欠谁多少钱(应付 = 期初 + 采购 − 退货 − 抵扣 − 付款,实时算)。'],
      ['常见问题', '钱盘对不上,用「资金调整单」作为唯一出口登记差异,不许直接改数字。'],
    ],
  },
  {
    em: '🔴', q: '驾驶舱的「红灯」是什么意思?怎么清零?',
    a: [
      ['这是什么', '系统帮你盯的风险点:负库存、待绑定、逾期应付、差异挂起、超期在途等。'],
      ['怎么做', '点红灯 → 跳到对应的待办去处理 → 处理完自动熄灯。目标是「红灯清零」= 账健康。'],
      ['常见问题', '红灯不是错误,是提醒;每天花几分钟清一遍,月底对账就轻松。'],
    ],
  },
  {
    em: '⚙️', q: '结算模式(平台/直营)怎么选?能改吗?',
    a: [
      ['这是什么', '全系统钱账口径的总开关:销售的钱是先归平台再结算(平台归集),还是直接进你商户号(直连)。'],
      ['怎么做', '「设置中心 → 参数与阈值」里定。核实清楚再定,定型前是「未定 UNSET」,报表只出假设预览。'],
      ['常见问题', '能切,但切换后旧单要作废重录、资产快照会变——所以别频繁改,想清楚再定。'],
    ],
  },
  {
    em: '📊', q: 'BI / 月度报表 / PDCA 复盘看什么?',
    a: [
      ['这是什么', '经营分析:哪台机器赚钱、哪个商品好卖、这月比上月怎么样,以及改进闭环。'],
      ['怎么做', '「BI 经营分析」看趋势和排行;「月度报表」出整月的账;「改进循环 PDCA」把发现的问题落成行动项跟进。'],
      ['常见问题', '数据来自日常导入,导得越勤,分析越准;AI 分析旁边都能点开看依据和推理过程。'],
    ],
  },
]
const kw = ref('')
const filteredFaqs = computed(() => {
  const k = kw.value.trim()
  if (!k) return faqs
  return faqs.filter((f) => f.q.includes(k) || f.a.some((row) => row[1].includes(k)))
})
</script>

<template>
  <div class="ledger-page">
    <div class="ledger-crumb">园区小卖 ERP / 帮助 / 新手指引</div>
    <div class="ledger-title">
      <h2>新手指引</h2>
      <span class="sub">从这里开始 · 不懂就翻这里</span>
    </div>
    <p class="ledger-note">
      <b>三步让系统跑起来</b>,每天照着「速查卡」做,遇到不会的翻「帮助手册」,或点任意页右下角的
      <b>「?」</b>。
    </p>

    <!-- 全绿横幅 -->
    <el-alert v-if="allDone" type="success" :closable="false" class="mb-14px"
      title="🎉 系统已就绪 · 三步全部完成,可以正常使用啦" />
    <el-alert v-else type="warning" :closable="false" class="mb-14px"
      :title="`系统还没完全开张 —— 还差 ${3 - doneCount} 步,跟着下面做完就能用`" />

    <!-- A. 开业向导 -->
    <el-card shadow="never" class="mb-16px">
      <div class="flex items-center justify-between flex-wrap gap-8px mb-8px">
        <div class="text-16px font-bold"><span class="mr-6px">🚩</span>开业向导 · 让系统跑起来</div>
        <div class="flex items-center gap-10px text-13px text-gray-500">
          <span>已完成 <b style="color: var(--green)">{{ doneCount }} / 3</b></span>
          <span class="prog-bar"><i :style="{ width: (doneCount / 3 * 100) + '%' }" /></span>
        </div>
      </div>

      <el-collapse v-model="activeStep" accordion>
        <!-- 第1步 -->
        <el-collapse-item name="1">
          <template #title>
            <span class="step-badge" :class="step1Done ? 'ok' : 'no'">{{ step1Done ? '✓' : '1' }}</span>
            <span class="step-title">建档案(设置中心)</span>
            <el-tag :type="step1Done ? 'success' : 'warning'" size="small" effect="light" class="ml-10px">
              {{ step1Done ? '已完成' : `还差 ${step1Left} 项` }}
            </el-tag>
          </template>
          <div class="sub-item" v-for="it in step1Items" :key="it.key">
            <span class="tick" :class="it.ok ? 'ok' : 'no'">{{ it.ok ? '✓' : '' }}</span>
            <div class="si-txt">
              {{ it.label }}
              <small>· {{ it.tip }}{{ it.ok && it.count ? ` · 已录 ${it.count}` : '' }}</small>
            </div>
            <el-button size="small" :type="it.ok ? 'default' : 'primary'" :plain="it.ok" @click="go(it.to)">
              {{ it.ok ? '去查看' : '去录入' }} →
            </el-button>
          </div>
        </el-collapse-item>

        <!-- 第2步 -->
        <el-collapse-item name="2">
          <template #title>
            <span class="step-badge" :class="step2Done ? 'ok' : 'no'">{{ step2Done ? '✓' : '2' }}</span>
            <span class="step-title">导期初数据(上线一次性)</span>
            <el-tag :type="step2Done ? 'success' : 'info'" size="small" effect="light" class="ml-10px">
              {{ step2Done ? '已完成' : '未开始' }}
            </el-tag>
          </template>
          <p class="step-desc">
            老 Excel 进销存套表搬家:① 商品档案 + 别名(一码多品清洗)→ ② 历史采购(建加权成本)→
            ③ 历史销售 → 与老账对平才算「期初完成」。
          </p>
          <el-button type="primary" @click="go('/import')">进入向导 →</el-button>
        </el-collapse-item>

        <!-- 第3步 -->
        <el-collapse-item name="3">
          <template #title>
            <span class="step-badge" :class="step3Done ? 'ok' : 'no'">{{ step3Done ? '✓' : '3' }}</span>
            <span class="step-title">对平验收</span>
            <el-tag :type="step3Done ? 'success' : 'info'" size="small" effect="light" class="ml-10px">
              {{ step3Done ? '已完成' : '未开始' }}
            </el-tag>
          </template>
          <p class="step-desc">
            库存 / 资产家底与老账核对,驾驶舱「红灯清零」= 正式可用。
          </p>
          <el-button :type="step3Done ? 'default' : 'primary'" :plain="step3Done" @click="go('/dashboard')">
            去驾驶舱 →
          </el-button>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <!-- B. 每日速查卡 -->
    <el-card shadow="never" class="mb-16px">
      <div class="flex items-center justify-between flex-wrap gap-8px mb-12px">
        <div class="text-16px font-bold"><span class="mr-6px">📆</span>每天该干嘛 · 速查卡</div>
        <el-button plain @click="startTour(true)">▶ 带我逛一圈</el-button>
      </div>
      <div class="flow">
        <template v-for="(f, i) in dailyFlow" :key="f.t">
          <div class="fstep" @click="go(f.to)">
            <div class="fn">{{ f.n }}</div>
            <div class="ft">{{ f.t }}</div>
            <div class="fd">{{ f.d }}</div>
          </div>
          <div v-if="i < dailyFlow.length - 1" class="farrow">→</div>
        </template>
      </div>
      <div class="flow-note">手工只需录一种单:采购到货 → 「采购入库」。其余全靠导入自动记账。</div>
    </el-card>

    <!-- C. 帮助手册 -->
    <el-card shadow="never">
      <div class="text-16px font-bold mb-10px"><span class="mr-6px">📖</span>帮助手册 · 不懂就翻这里</div>
      <el-input v-model="kw" placeholder="🔍 搜一下,例如:怎么导入出货明细 / 负库存红灯 / 结算模式" clearable class="mb-12px" />
      <el-collapse>
        <el-collapse-item v-for="(f, i) in filteredFaqs" :key="i" :name="String(i)">
          <template #title><span class="mr-8px text-16px">{{ f.em }}</span>{{ f.q }}</template>
          <p v-for="row in f.a" :key="row[0]" class="faq-a"><b>{{ row[0] }}:</b>{{ row[1] }}</p>
        </el-collapse-item>
      </el-collapse>
      <el-empty v-if="!filteredFaqs.length" description="没搜到,换个词试试" :image-size="70" />
    </el-card>
  </div>
</template>

<style scoped>
.prog-bar {
  width: 140px;
  height: 8px;
  background: var(--green-soft);
  border-radius: 99px;
  overflow: hidden;
  display: inline-block;
}
.prog-bar > i {
  display: block;
  height: 100%;
  background: var(--green);
  border-radius: 99px;
  transition: width 0.4s ease;
}
.step-badge {
  width: 24px;
  height: 24px;
  border-radius: 99px;
  display: inline-grid;
  place-items: center;
  font-size: 13px;
  font-weight: 700;
  margin-right: 10px;
  flex-shrink: 0;
}
.step-badge.ok {
  background: var(--green);
  color: #fff;
}
.step-badge.no {
  background: #eee9dd;
  color: #a39a83;
  border: 1.5px solid var(--line2);
}
.step-title {
  font-weight: 700;
  font-size: 15px;
}
.step-desc {
  font-size: 13px;
  color: var(--ink2);
  margin: 4px 0 12px;
  line-height: 1.7;
}
.sub-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px dashed var(--line);
}
.sub-item:last-child {
  border-bottom: 0;
}
.tick {
  width: 20px;
  height: 20px;
  border-radius: 99px;
  display: grid;
  place-items: center;
  font-size: 12px;
  flex-shrink: 0;
  color: #fff;
}
.tick.ok {
  background: var(--green);
}
.tick.no {
  background: #fff;
  border: 1.5px solid var(--line2);
}
.si-txt {
  font-size: 14px;
}
.si-txt small {
  color: var(--ink2);
  font-size: 12px;
}
.sub-item .el-button {
  margin-left: auto;
}
/* 速查卡 */
.flow {
  display: flex;
  align-items: stretch;
  flex-wrap: wrap;
  gap: 0;
}
.fstep {
  flex: 1;
  min-width: 120px;
  background: #fbfaf4;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.18s;
}
.fstep:hover {
  border-color: var(--green);
  box-shadow: 0 2px 8px rgba(47, 110, 82, 0.12);
}
.fn {
  font-family: var(--serif);
  font-size: 13px;
  color: var(--green);
  font-weight: 800;
}
.ft {
  font-weight: 700;
  font-size: 13.5px;
  margin: 4px 0 3px;
}
.fd {
  font-size: 11.5px;
  color: var(--ink2);
  line-height: 1.5;
}
.farrow {
  align-self: center;
  color: var(--line2);
  font-size: 20px;
  padding: 0 6px;
}
.flow-note {
  font-size: 12px;
  color: var(--ink2);
  text-align: center;
  margin-top: 10px;
}
.faq-a {
  font-size: 13px;
  color: var(--ink2);
  line-height: 1.7;
  margin: 6px 0;
}
.faq-a b {
  color: var(--ink);
}
@media (max-width: 768px) {
  .flow {
    flex-direction: column;
  }
  .farrow {
    transform: rotate(90deg);
    padding: 2px 0;
  }
  .prog-bar {
    width: 90px;
  }
}
</style>
