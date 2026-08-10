export interface ApiErrorBody {
  code: string
  message: string
  requestId: string
  occurredAt: string
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: ApiErrorBody,
  ) {
    super(body.message)
  }
}

interface CsrfResponse {
  headerName: string
  parameterName: string
  token: string
}

let csrfToken: CsrfResponse | null = null

const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

async function loadCsrf(): Promise<CsrfResponse> {
  const response = await fetch('/api/v1/csrf', { credentials: 'include', headers: { Accept: 'application/json' } })
  if (!response.ok) {
    throw await toApiError(response)
  }
  csrfToken = (await response.json()) as CsrfResponse
  return csrfToken
}

async function toApiError(response: Response): Promise<ApiError> {
  const fallback: ApiErrorBody = {
    code: `HTTP_${response.status}`,
    message: '请求失败，请稍后重试',
    requestId: response.headers.get('X-Request-Id') ?? 'unknown',
    occurredAt: new Date().toISOString(),
  }
  let body = fallback
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    // Keep the safe fallback when a proxy returns a non-JSON error.
  }
  if (response.status === 503) {
    window.dispatchEvent(new CustomEvent('fw:service-unavailable', { detail: body }))
  }
  return new ApiError(response.status, body)
}

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (unsafeMethods.has(method)) {
    const csrf = csrfToken ?? (await loadCsrf())
    headers.set(csrf.headerName, csrf.token)
  }

  const response = await fetch(`/api/v1${path}`, { ...init, method, headers, credentials: 'include' })
  if (!response.ok) {
    if (response.status === 403 && unsafeMethods.has(method)) {
      csrfToken = null
    }
    throw await toApiError(response)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export function resetCsrf(): void {
  csrfToken = null
}

