import { useEffect, useState, type SubmitEvent } from 'react'
import {
  Link,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom'
import { apiRequest, QueueRedirectError } from '../services/apiClient'
import './ReservationPage.css'

type ReservationFeedback = {
  type: 'success' | 'error'
  message: string
  reservationId?: string
}

const RESERVATION_API_PATH = '/api/v1/reservation'

function ReservationPage() {
  const { seatId: seatIdParam } = useParams()
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const [bookerName, setBookerName] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [feedback, setFeedback] = useState<ReservationFeedback | null>(null)

  const seatId = Number(seatIdParam)
  const isValidSeatId = Number.isSafeInteger(seatId) && seatId > 0
  const seatNo = searchParams.get('seatNo')

  useEffect(() => {
    if (feedback?.type !== 'success') {
      return
    }

    const timeoutId = window.setTimeout(() => {
      navigate('/')
    }, 1500)

    return () => {
      window.clearTimeout(timeoutId)
    }
  }, [feedback, navigate])

  const handleSubmit = async (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault()
    setFeedback(null)

    const trimmedBookerName = bookerName.trim()

    if (!trimmedBookerName) {
      setFeedback({
        type: 'error',
        message: '예매자 이름을 입력해 주세요.',
      })
      return
    }

    if (trimmedBookerName.length > 100) {
      setFeedback({
        type: 'error',
        message: '예매자 이름은 100자 이하로 입력해 주세요.',
      })
      return
    }

    if (!isValidSeatId) {
      setFeedback({
        type: 'error',
        message: '선택한 좌석 정보를 확인할 수 없습니다.',
      })
      return
    }

    try {
      setIsSubmitting(true)

      const response = await apiRequest(RESERVATION_API_PATH, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          bookerName: trimmedBookerName,
          seatId,
        }),
      })

      const responseBody = (await response.text()).trim()

      if (!response.ok) {
        throw new Error(responseBody || '좌석 예매에 실패했습니다.')
      }

      if (!/^\d+$/.test(responseBody)) {
        throw new Error('예약 결과를 확인할 수 없습니다.')
      }

      setFeedback({
        type: 'success',
        message:
          '좌석 예매가 완료되었습니다. 잠시 후 좌석 목록으로 이동합니다.',
        reservationId: responseBody,
      })
    } catch (error) {
      if (error instanceof QueueRedirectError) {
        return
      }

      const message =
        error instanceof Error
          ? error.message
          : '알 수 없는 오류가 발생했습니다.'

      setFeedback({ type: 'error', message })
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <main className="reservation-page">
      <header className="reservation-page__header">
        <p>좌석 예매</p>
        <h1>예매 정보 확인</h1>
        <span>선택한 좌석과 예매자 정보를 확인해 주세요.</span>
      </header>

      <section className="reservation-card">
        <div className="reservation-card__seat">
          <span>선택 좌석</span>
          <strong>{seatNo || `좌석 ID ${seatIdParam ?? '-'}`}</strong>
        </div>

        <form className="reservation-form" onSubmit={handleSubmit}>
          <label htmlFor="bookerName">예매자 이름</label>
          <input
            id="bookerName"
            name="bookerName"
            type="text"
            autoComplete="name"
            maxLength={100}
            value={bookerName}
            disabled={isSubmitting}
            onChange={(event) => setBookerName(event.target.value)}
            placeholder="예: 홍길동"
            required
          />

          <button type="submit" disabled={isSubmitting || !isValidSeatId}>
            {isSubmitting ? '예매 처리 중...' : '예매 확정'}
          </button>
        </form>

        {feedback && (
          <div
            className={`reservation-feedback reservation-feedback--${feedback.type}`}
            role={feedback.type === 'error' ? 'alert' : 'status'}
          >
            <strong>{feedback.message}</strong>
            {feedback.reservationId && (
              <span>예약 번호: {feedback.reservationId}</span>
            )}
          </div>
        )}
      </section>

      <Link className="reservation-page__back-link" to="/">
        좌석 목록으로 돌아가기
      </Link>
    </main>
  )
}

export default ReservationPage
