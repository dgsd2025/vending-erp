/** 会话（2026-08-19 邀请码注册上线）：token 由 /api/auth/register|login 签发，请求统一带 Authorization。 */
const TOKEN_KEY = 'vend_token'
export function getToken(): string | null { return localStorage.getItem(TOKEN_KEY) }
export function setSession(token: string, displayName: string, role: string): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem('vend_user_name', displayName)
  localStorage.setItem('vend_user_role', role)
}
export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem('vend_user_name')
  localStorage.removeItem('vend_user_role')
}
export function isLoggedIn(): boolean { return !!getToken() }
