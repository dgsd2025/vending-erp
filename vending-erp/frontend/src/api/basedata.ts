import request from '@/utils/request'

/**
 * 基础档案 API(M1-2):商品 / SKU别名 / 供应商 / 机器与货道 / 补货参数 / 留痕查询。
 * 后端统一 R{code,message,data},request 已解包出 data。
 * 经手人:先用 header X-User-Name 占位(中文需 encodeURIComponent,后端会解码),SSO 后替换。
 */

// ---------- 类型 ----------

export interface PageResult<T> {
  records: T[]
  total: number
  size: number
  current: number
}

export interface Product {
  id?: number
  skuCode: string
  legacyCode?: string | null
  productName: string
  barcode?: string | null
  category?: string | null
  unit?: string
  boxSpec?: number | string | null
  shelfLifeDays?: number | null
  refCost?: number | string | null
  refPrice?: number | string | null
  productStatus?: string
  clearanceSince?: string | null
  minDisplayQty?: number | string | null
  remark?: string | null
  createTime?: string
}

export interface SkuAlias {
  id?: number
  aliasCode: string
  aliasBarcode: string
  aliasName: string
  productId: number
  bindSource?: string
  aiConfidence?: number | null
  createTime?: string
}

export interface AliasPending {
  id: number
  aliasCode: string
  aliasBarcode: string
  aliasName: string
  hitCount: number
  suggestProductId?: number | null
  aiConfidence?: number | null
  llmCallId?: number | null
  pendingStatus: string
  createTime?: string
}

export interface Supplier {
  id?: number
  supplierCode: string
  supplierName: string
  contact?: string | null
  settleMethod?: string
  accountDays?: number
  openingPayable?: number | string
  coopStatus?: string
  remark?: string | null
}

export interface Machine {
  id?: number
  machineCode: string
  machineName: string
  deviceId: string
  location?: string | null
  model?: string | null
  slotCount?: number | null
  machineStatus?: string
  onlineDate?: string | null
  remark?: string | null
}

export interface Slot {
  id: number
  machineId: number
  slotNo: string
  productId?: number | null
  capacity: number | string
  currentQty: number | string
  slotStatus: string
}

export interface ReplenishConfig {
  id?: number
  scopeType?: string
  productId?: number
  machineId?: number
  cycleDays?: number | null
  serviceLevel?: number | string | null
  leadTimeDays?: number | string | null
  expireWarnDays?: number | null
  slowMinBoxes?: number | string | null
  slowMaxBoxes?: number | string | null
  llmModel?: string | null
}

export interface OpLog {
  id: number
  userName: string
  action: string
  targetType: string
  targetId: number
  beforeJson?: string | null
  afterJson?: string | null
  opTime: string
}

export interface PriceLog {
  id: number
  productId: number
  oldPrice?: number | null
  newPrice: number
  changeSource: string
  effectDate?: string | null
  createTime?: string
}

// ---------- 经手人占位 ----------

/** 取当前经手人姓名(SSO 前占位,可在 localStorage 里改 vend_user_name) */
export function currentUserName(): string {
  return localStorage.getItem('vend_user_name') || '演示用户'
}

function opHeaders() {
  return { headers: { 'X-User-Name': encodeURIComponent(currentUserName()) } }
}

// ---------- 商品 ----------

export function pageProducts(params: {
  current?: number
  size?: number
  keyword?: string
  category?: string
  productStatus?: string
}): Promise<PageResult<Product>> {
  return request.get('/v1/basedata/products', { params })
}

export function getProduct(id: number): Promise<Product> {
  return request.get(`/v1/basedata/products/${id}`)
}

export function createProduct(data: Product): Promise<Product> {
  return request.post('/v1/basedata/products', data, opHeaders())
}

export function updateProduct(id: number, data: Product): Promise<Product> {
  return request.put(`/v1/basedata/products/${id}`, data, opHeaders())
}

export function changeProductStatus(id: number, targetStatus: string): Promise<Product> {
  return request.put(`/v1/basedata/products/${id}/status`, { targetStatus }, opHeaders())
}

// ---------- SKU 别名 ----------

export function pageAliases(params: {
  current?: number
  size?: number
  productId?: number
  keyword?: string
}): Promise<PageResult<SkuAlias>> {
  return request.get('/v1/basedata/aliases', { params })
}

export function bindAlias(data: {
  aliasCode?: string
  aliasBarcode?: string
  aliasName: string
  productId: number
}): Promise<SkuAlias> {
  return request.post('/v1/basedata/aliases/bind', data, opHeaders())
}

export function unbindAlias(id: number): Promise<void> {
  return request.delete(`/v1/basedata/aliases/${id}`, opHeaders())
}

export function pageAliasPending(params: {
  current?: number
  size?: number
  pendingStatus?: string
}): Promise<PageResult<AliasPending>> {
  return request.get('/v1/basedata/alias-pending', { params })
}

export function confirmAliasPending(id: number, productId: number): Promise<SkuAlias> {
  return request.post(`/v1/basedata/alias-pending/${id}/confirm`, { productId }, opHeaders())
}

export function ignoreAliasPending(id: number): Promise<void> {
  return request.post(`/v1/basedata/alias-pending/${id}/ignore`, {}, opHeaders())
}

// ---------- 供应商 ----------

export function pageSuppliers(params: {
  current?: number
  size?: number
  keyword?: string
  coopStatus?: string
}): Promise<PageResult<Supplier>> {
  return request.get('/v1/basedata/suppliers', { params })
}

export function createSupplier(data: Supplier): Promise<Supplier> {
  return request.post('/v1/basedata/suppliers', data, opHeaders())
}

export function updateSupplier(id: number, data: Supplier): Promise<Supplier> {
  return request.put(`/v1/basedata/suppliers/${id}`, data, opHeaders())
}

export function changeSupplierStatus(id: number, targetStatus: string): Promise<Supplier> {
  return request.put(`/v1/basedata/suppliers/${id}/status`, { targetStatus }, opHeaders())
}

// ---------- 机器与货道 ----------

export function pageMachines(params: {
  current?: number
  size?: number
  keyword?: string
  machineStatus?: string
}): Promise<PageResult<Machine>> {
  return request.get('/v1/basedata/machines', { params })
}

export function createMachine(data: Machine): Promise<Machine> {
  return request.post('/v1/basedata/machines', data, opHeaders())
}

export function updateMachine(id: number, data: Machine): Promise<Machine> {
  return request.put(`/v1/basedata/machines/${id}`, data, opHeaders())
}

export function changeMachineStatus(id: number, targetStatus: string): Promise<Machine> {
  return request.put(`/v1/basedata/machines/${id}/status`, { targetStatus }, opHeaders())
}

export function listSlots(machineId: number): Promise<Slot[]> {
  return request.get(`/v1/basedata/machines/${machineId}/slots`)
}

export function initSlots(
  machineId: number,
  data: { slotNos?: string[]; slotCount?: number; capacity?: number },
): Promise<Slot[]> {
  return request.post(`/v1/basedata/machines/${machineId}/slots/init`, data, opHeaders())
}

export function updateSlot(
  slotId: number,
  data: { productId?: number; capacity?: number; slotStatus?: string },
): Promise<Slot> {
  return request.put(`/v1/basedata/slots/${slotId}`, data, opHeaders())
}

// ---------- 补货参数 ----------

export function listReplenishConfigs(): Promise<ReplenishConfig[]> {
  return request.get('/v1/basedata/replenish-configs')
}

export function getGlobalReplenishConfig(): Promise<ReplenishConfig> {
  return request.get('/v1/basedata/replenish-configs/global')
}

export function saveReplenishConfig(data: ReplenishConfig): Promise<ReplenishConfig> {
  return request.put('/v1/basedata/replenish-configs', data, opHeaders())
}

export function deleteReplenishConfig(id: number): Promise<void> {
  return request.delete(`/v1/basedata/replenish-configs/${id}`, opHeaders())
}

// ---------- 留痕查询 ----------

export function pageOpLogs(params: {
  current?: number
  size?: number
  targetType?: string
  targetId?: number
}): Promise<PageResult<OpLog>> {
  return request.get('/v1/basedata/op-logs', { params })
}

export function pagePriceLogs(params: {
  current?: number
  size?: number
  productId?: number
}): Promise<PageResult<PriceLog>> {
  return request.get('/v1/basedata/price-logs', { params })
}
