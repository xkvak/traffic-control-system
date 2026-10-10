import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { saveAccessToken } from '../services/apiClient'
import './QueuePage.css'

type QueueStatus =
  | 'connecting'
  | 'waiting'
  | 'reconnecting'
  | 'allowed'
  | 'expired'
  | 'error'

type QueueEventPayload = {
  status?: string
  rank?: number
  token?: string
  requestedUri?: string
}

function parseQueueEvent(event: Event) {
  if (!(event instanceof MessageEvent) || typeof event.data !== 'string') {
    return null
  }

  try {
    return JSON.parse(event.data) as QueueEventPayload
  } catch {
    return null
  }
}

function resolveReturnPath(requestedUri: string | null | undefined) {
  if (!requestedUri) {
    return '/'
  }

  try {
    const resolved = new URL(requestedUri, window.location.origin)

    if (resolved.origin !== window.location.origin) {
      return '/'
    }

    if (
      resolved.pathname.startsWith('/api/') ||
      resolved.pathname.startsWith('/turnstile/')
    ) {
      return '/'
    }

    return `${resolved.pathname}${resolved.search}${resolved.hash}`
  } catch {
    return '/'
  }
}

function QueuePage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [status, setStatus] = useState<QueueStatus>('connecting')
  const [rank, setRank] = useState<number | null>(null)
  const [message, setMessage] = useState('대기열 서버에 연결하고 있습니다.')

  const requestId = searchParams.get('requestId')
  const requestedUri = searchParams.get('requestedUri')

  useEffect(() => {
    if (!requestId) {
      return
    }

    const eventUrl = new URL('/turnstile/queue/events', window.location.origin)
    eventUrl.searchParams.set('requestId', requestId)

    if (requestedUri) {
      eventUrl.searchParams.set('requestedUri', requestedUri)
    }

    const eventSource = new EventSource(eventUrl)

    eventSource.onopen = () => {
      setStatus('connecting')
      setMessage('대기 순번을 확인하고 있습니다.')
    }

    const handleWaiting = (event: Event) => {
      const payload = parseQueueEvent(event)
      const nextRank =
        typeof payload?.rank === 'number' && payload.rank > 0
          ? payload.rank
          : null

      setStatus('waiting')
      setRank(nextRank)
      setMessage(
        nextRank
          ? `현재 대기 순번은 ${nextRank}번입니다.`
          : '현재 대기 순번을 확인하고 있습니다.',
      )
    }

    const handleAllowed = (event: Event) => {
      const payload = parseQueueEvent(event)

      if (!payload?.token) {
        setStatus('error')
        setMessage('입장 토큰을 확인할 수 없습니다.')
        eventSource.close()
        return
      }

      saveAccessToken(payload.token)
      setStatus('allowed')
      setRank(null)
      setMessage('입장이 허용되었습니다. 이전 페이지로 이동합니다.')
      eventSource.close()
      navigate(
        resolveReturnPath(payload.requestedUri || requestedUri),
        { replace: true },
      )
    }

    const handleExpired = () => {
      setStatus('expired')
      setRank(null)
      setMessage('대기열 요청이 만료되었습니다. 다시 시도해 주세요.')
      eventSource.close()
    }

    eventSource.addEventListener('waiting', handleWaiting)
    eventSource.addEventListener('allowed', handleAllowed)
    eventSource.addEventListener('expired', handleExpired)

    eventSource.onerror = () => {
      if (eventSource.readyState === EventSource.CONNECTING) {
        setStatus('reconnecting')
        setMessage('연결이 끊어져 다시 연결하고 있습니다.')
        return
      }

      setStatus('error')
      setMessage('대기열 서버에 연결할 수 없습니다.')
    }

    return () => {
      eventSource.removeEventListener('waiting', handleWaiting)
      eventSource.removeEventListener('allowed', handleAllowed)
      eventSource.removeEventListener('expired', handleExpired)
      eventSource.close()
    }
  }, [navigate, requestId, requestedUri])

  const displayedStatus = requestId ? status : 'error'
  const displayedMessage = requestId
    ? message
    : '대기열 요청 정보를 확인할 수 없습니다.'
  const isActive =
    displayedStatus === 'connecting' ||
    displayedStatus === 'waiting' ||
    displayedStatus === 'reconnecting'

  return (
    <main className="queue-page">
      <header className="queue-page__header">
        <p>Turnstile Queue</p>
        <h1>접속 대기 중</h1>
        <span>잠시만 기다리면 자동으로 입장됩니다.</span>
      </header>

      <section className="queue-card" aria-live="polite">
        {isActive && <div className="queue-card__spinner" aria-hidden="true" />}

        {rank && (
          <div className="queue-card__rank">
            <span>현재 순번</span>
            <strong>{rank}</strong>
          </div>
        )}

        <p
          className={`queue-card__message queue-card__message--${displayedStatus}`}
        >
          {displayedMessage}
        </p>

        {(displayedStatus === 'expired' || displayedStatus === 'error') && (
          <Link className="queue-card__retry-link" to="/">
            좌석 페이지에서 다시 시도하기
          </Link>
        )}
      </section>
    </main>
  )
}

export default QueuePage
