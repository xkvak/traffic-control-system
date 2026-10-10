const ACCESS_TOKEN_KEY = 'turnstile.access-token'
const CURRENT_PAGE_URI_HEADER = 'X-Current-Page-Uri'

type QueueAdmissionResponse = {
  status?: string
  queuePagePath?: string
}

export class QueueRedirectError extends Error {
  constructor() {
    super('대기열 페이지로 이동 중입니다.')
    this.name = 'QueueRedirectError'
  }
}

export function getAccessToken() {
  return window.sessionStorage.getItem(ACCESS_TOKEN_KEY)
}

export function saveAccessToken(token: string) {
  window.sessionStorage.setItem(ACCESS_TOKEN_KEY, token)
}

export async function apiRequest(
  input: string | URL | Request,
  init: RequestInit = {},
) {
  const headers = new Headers(init.headers)
  const accessToken = getAccessToken()

  headers.set(
    CURRENT_PAGE_URI_HEADER,
    `${window.location.pathname}${window.location.search}`,
  )

  if (accessToken && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${accessToken}`)
  }

  const response = await fetch(input, {
    ...init,
    headers,
  })

  if (response.status !== 202) {
    return response
  }

  const payload = (await response.json()) as QueueAdmissionResponse

  if (payload.status !== 'QUEUED' || !payload.queuePagePath) {
    throw new Error('대기열 이동 정보를 확인할 수 없습니다.')
  }

  const redirectUrl = new URL(payload.queuePagePath, window.location.origin)

  if (redirectUrl.protocol !== 'http:' && redirectUrl.protocol !== 'https:') {
    throw new Error('유효하지 않은 대기열 페이지 주소입니다.')
  }

  window.location.assign(redirectUrl.toString())
  throw new QueueRedirectError()
}
