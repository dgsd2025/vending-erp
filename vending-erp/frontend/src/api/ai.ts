import request from '@/utils/request'

/**
 * AI 接入点全家桶 API(M4-5)。mock 网关,真 key 后一键启用;后端 R 已解包出 data。
 */

export interface AiModelRow {
  key: string
  label: string
  description: string
  defaultModel: string
  configuredModel: string | null
  effectiveModel: string
  phase: number
  wired: boolean
}

/** 透明四件套(🔬 弹窗) */
export interface LlmTransparency {
  id: number
  scene: string
  model: string
  promptFingerprint: string | null
  callStatus: string
  cacheHit: boolean
  createTime: string
  reasoning: string | null
  outputText: string | null
  confidence: number | null
  inputDigest: string | null
  cost: {
    tokensIn: number | null
    tokensOut: number | null
    costAmount: number | null
    durationMs: number | null
  }
}

export interface Anomaly {
  key: string
  type: string
  title: string
  severity: string
  metrics: Record<string, unknown>
}

export interface NlQueryResult {
  question: string
  matchedTemplate: string
  sql: string
  rows: Array<Record<string, unknown>>
  llmCallId: number
  cacheHit: boolean
  mode: string
}

export const aiApi = {
  // 设置中心:模型配置
  models: (): Promise<AiModelRow[]> => request.get('/v1/ai/models'),
  setModel: (scene: string, model: string): Promise<void> =>
    request.put('/v1/ai/models', { scene, model }),

  // #1 别名建议
  aliasSuggest: (force = false): Promise<Record<string, unknown>> =>
    request.post('/v1/ai/alias/suggest', null, { params: { force } }),

  // #2 凭证识别
  ocr: (attachmentId: number, force = false): Promise<Record<string, unknown>> =>
    request.post(`/v1/ai/ocr/${attachmentId}`, null, { params: { force } }),

  // #3 解释层
  insight: (subScene: string, force = false): Promise<Record<string, unknown>> =>
    request.get(`/v1/ai/insight/${subScene}`, { params: { force } }),

  // #4 异常侦探
  anomalies: (): Promise<Anomaly[]> => request.get('/v1/ai/anomalies'),
  anomalyExplain: (anomaly: Anomaly, force = false): Promise<Record<string, unknown>> =>
    request.post('/v1/ai/anomalies/explain', anomaly, { params: { force } }),

  // #6 NL 查数
  nlQuery: (q: string, force = false): Promise<NlQueryResult> =>
    request.get('/v1/ai/nl-query', { params: { q, force } }),
  nlSuggestions: (): Promise<string[]> => request.get('/v1/ai/nl-query/suggestions'),

  // 🔬 透明四件套
  transparency: (llmCallId: number): Promise<LlmTransparency> =>
    request.get(`/v1/ai/transparency/${llmCallId}`),
}
