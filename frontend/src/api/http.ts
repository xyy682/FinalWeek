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

export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    headers,
    credentials: 'include',
  })
  if (!response.ok) {
    throw new ApiError(response.status, (await response.json()) as ApiErrorBody)
  }
  return (await response.json()) as T
}

