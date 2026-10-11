const ACCESS_TOKEN_KEY = 'turnstile.access-token'
const DEFAULT_REFRESH_MS = 20_000
const REFRESH_RETRY_MS = 5_000

export type QueueStatus = {
  status: 'ALLOWED' | 'WAITING' | 'EXPIRED' | 'CLOSED' | 'FULL'
  rank?: number
  retryAfterMs?: number
  token?: string
  refreshAfterMs?: number
}

export class QueueRedirectError extends Error {
  constructor() {
    super('대기열 페이지로 이동 중입니다.')
    this.name = 'QueueRedirectError'
  }
}

export class QueueUnavailableError extends Error {
  readonly retryAfterMs?: number

  constructor(retryAfterMs?: number) {
    super('대기열 서버에 연결할 수 없습니다.')
    this.name = 'QueueUnavailableError'
    this.retryAfterMs = retryAfterMs
  }
}

let refreshTimer: number | undefined

export function getAccessToken() {
  return window.sessionStorage.getItem(ACCESS_TOKEN_KEY)
}

export function saveAccessToken(token: string, refreshAfterMs = DEFAULT_REFRESH_MS) {
  window.sessionStorage.setItem(ACCESS_TOKEN_KEY, token)
  scheduleRefresh(refreshAfterMs)
}

function clearAccessToken() {
  window.clearTimeout(refreshTimer)
  window.sessionStorage.removeItem(ACCESS_TOKEN_KEY)
}

function scheduleRefresh(delayMs: number) {
  window.clearTimeout(refreshTimer)
  refreshTimer = window.setTimeout(refreshSession, delayMs)
}

async function refreshSession() {
  const token = getAccessToken()

  if (!token) {
    return
  }

  try {
    const response = await fetch('/queue/refresh', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    })

    if (response.status === 401) {
      clearAccessToken()
      return
    }

    if (!response.ok) {
      scheduleRefresh(REFRESH_RETRY_MS)
      return
    }

    const body = (await response.json()) as QueueStatus

    if (body.status === 'ALLOWED' && body.token) {
      saveAccessToken(body.token, body.refreshAfterMs)
    } else {
      clearAccessToken()
    }
  } catch {
    scheduleRefresh(REFRESH_RETRY_MS)
  }
}

export function resumeSession() {
  if (getAccessToken()) {
    scheduleRefresh(0)
  }
}

export async function leaveSession() {
  const token = getAccessToken()
  clearAccessToken()

  if (token) {
    await fetch('/queue/leave', {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    }).catch(() => undefined)
  }
}

function retryAfterMs(response: Response) {
  const seconds = Number(response.headers.get('Retry-After'))
  return Number.isFinite(seconds) && seconds > 0 ? seconds * 1000 : undefined
}

export async function enterQueue(): Promise<QueueStatus> {
  const response = await fetch('/queue/enter', { method: 'POST' })

  if (!response.ok) {
    throw new QueueUnavailableError(retryAfterMs(response))
  }

  return (await response.json()) as QueueStatus
}

export async function queueStatus(): Promise<QueueStatus | null> {
  const response = await fetch('/queue/status')

  if (response.status === 400) {
    return null
  }

  if (!response.ok) {
    throw new QueueUnavailableError(retryAfterMs(response))
  }

  return (await response.json()) as QueueStatus
}

async function admit() {
  const status = await enterQueue()

  if (status.status === 'ALLOWED' && status.token) {
    saveAccessToken(status.token, status.refreshAfterMs)
    return
  }

  const returnTo = `${window.location.pathname}${window.location.search}`
  window.location.assign(`/waiting?returnTo=${encodeURIComponent(returnTo)}`)
  throw new QueueRedirectError()
}

function send(input: string | URL | Request, init: RequestInit) {
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${getAccessToken()}`)
  return fetch(input, { ...init, headers })
}

export async function apiRequest(
  input: string | URL | Request,
  init: RequestInit = {},
) {
  if (!getAccessToken()) {
    await admit()
  }

  const response = await send(input, init)

  if (response.status !== 401) {
    return response
  }

  clearAccessToken()
  await admit()
  return send(input, init)
}
