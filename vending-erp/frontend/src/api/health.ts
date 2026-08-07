import request from '@/utils/request'

/** GET /api/v1/health → "vending-erp backend alive" */
export function getHealth(): Promise<string> {
  return request.get('/v1/health')
}
