import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import {
  enterQueue,
  queueStatus,
  QueueUnavailableError,
  saveAccessToken,
  type QueueStatus,
} from '../services/apiClient'
import './QueuePage.css'

type ViewStatus =
  | 'connecting'
  | 'waiting'
  | 'reconnecting'
  | 'allowed'
  | 'full'
  | 'closed'

const MAX_BACKOFF_MS = 30_000
const FULL_RETRY_MS = 15_000

/* 다른 사이트나 API 경로로 보내지 않도록, 같은 출처의 화면 경로만 허용한다 */
function resolveReturnPath(returnTo: string | null) {
  if (!returnTo) {
    return '/'
  }

  try {
    const resolved = new URL(returnTo, window.location.origin)

    if (
      resolved.origin !== window.location.origin ||
      ['/api/', '/queue/', '/waiting'].some((prefix) =>
        resolved.pathname.startsWith(prefix),
      )
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
  const [status, setStatus] = useState<ViewStatus>('connecting')
  const [rank, setRank] = useState<number | null>(null)
  const [message, setMessage] = useState('대기열 서버에 연결하고 있습니다.')

  const returnTo = resolveReturnPath(searchParams.get('returnTo'))

  useEffect(() => {
    let timer: number | undefined
    let stopped = false
    let failures = 0

    const schedule = (step: () => Promise<void>, delayMs: number) => {
      if (!stopped) {
        timer = window.setTimeout(step, delayMs)
      }
    }

    const retry = (error: unknown, step: () => Promise<void>) => {
      failures += 1
      const backoff =
        Math.min(MAX_BACKOFF_MS, 1_000 * 2 ** failures) *
        (0.8 + Math.random() * 0.4)
      const delay =
        error instanceof QueueUnavailableError && error.retryAfterMs
          ? error.retryAfterMs
          : backoff

      setStatus('reconnecting')
      setMessage('연결이 원활하지 않아 다시 시도하고 있습니다.')
      schedule(step, delay)
    }

    const apply = (queue: QueueStatus) => {
      failures = 0

      switch (queue.status) {
        case 'ALLOWED':
          if (queue.token) {
            saveAccessToken(queue.token, queue.refreshAfterMs)
          }
          setStatus('allowed')
          setRank(null)
          setMessage('입장이 허용되었습니다. 이전 페이지로 이동합니다.')
          navigate(returnTo, { replace: true })
          return
        case 'WAITING':
          setStatus('waiting')
          setRank(queue.rank ?? null)
          setMessage(
            queue.rank
              ? `현재 대기 순번은 ${queue.rank}번입니다.`
              : '현재 대기 순번을 확인하고 있습니다.',
          )
          schedule(poll, queue.retryAfterMs ?? 1_000)
          return
        case 'FULL':
          setStatus('full')
          setRank(null)
          setMessage('대기 인원이 가득 찼습니다. 잠시 후 자동으로 다시 시도합니다.')
          schedule(enter, FULL_RETRY_MS)
          return
        case 'CLOSED':
          setStatus('closed')
          setRank(null)
          setMessage('대기열이 마감되었습니다.')
          return
        case 'EXPIRED':
          schedule(enter, 0)
      }
    }

    const enter = async () => {
      try {
        const entered = await enterQueue()
        if (!stopped) {
          apply(entered)
        }
      } catch (error) {
        retry(error, enter)
      }
    }

    const poll = async () => {
      try {
        const current = await queueStatus()
        if (stopped) {
          return
        }
        if (current === null || current.status === 'EXPIRED') {
          await enter()
          return
        }
        apply(current)
      } catch (error) {
        retry(error, poll)
      }
    }

    poll()

    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [navigate, returnTo])

  const isActive =
    status === 'connecting' || status === 'waiting' || status === 'reconnecting'

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

        <p className={`queue-card__message queue-card__message--${status}`}>
          {message}
        </p>

        {status === 'closed' && (
          <Link className="queue-card__retry-link" to="/">
            좌석 페이지로 돌아가기
          </Link>
        )}
      </section>
    </main>
  )
}

export default QueuePage
