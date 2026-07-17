import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import SeatItem, { type SeatStatus } from '../components/SeatItem'
import { apiRequest, QueueRedirectError } from '../services/apiClient'
import './SeatPage.css'

type Seat = {
  id: number
  seatNo: string
  status: SeatStatus
}

type ResetFeedback = {
  type: 'success' | 'error'
  message: string
}

const SEATS_API_PATH = '/api/v1/concerts/seats'

async function requestSeats() {
  const response = await apiRequest(SEATS_API_PATH)

  if (!response.ok) {
    throw new Error('좌석 정보를 불러오지 못했습니다.')
  }

  return (await response.json()) as Seat[]
}

function SeatPage() {
  const navigate = useNavigate()
  const [seats, setSeats] = useState<Seat[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [isResetting, setIsResetting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [resetFeedback, setResetFeedback] = useState<ResetFeedback | null>(
    null,
  )

  useEffect(() => {
    const fetchSeats = async () => {
      try {
        setIsLoading(true)
        setError(null)
        setSeats(await requestSeats())
      } catch (error) {
        if (error instanceof QueueRedirectError) {
          return
        }

        const message =
          error instanceof Error
            ? error.message
            : '알 수 없는 오류가 발생했습니다.'

        setError(message)
      } finally {
        setIsLoading(false)
      }
    }

    fetchSeats()
  }, [])

  const handleSeatClick = (seat: Seat) => {
    navigate(
      `/reservation/${seat.id}?seatNo=${encodeURIComponent(seat.seatNo)}`,
    )
  }

  const handleResetSeats = async () => {
    const shouldReset = window.confirm(
      '모든 좌석 예매 내역을 초기화하시겠습니까?\n이 작업은 되돌릴 수 없습니다.',
    )

    if (!shouldReset) {
      return
    }

    try {
      setIsResetting(true)
      setResetFeedback(null)

      const response = await apiRequest(SEATS_API_PATH, {
        method: 'DELETE',
      })

      if (!response.ok) {
        throw new Error('좌석 예매를 초기화하지 못했습니다.')
      }

      setSeats(await requestSeats())
      setResetFeedback({
        type: 'success',
        message: '좌석 예매가 초기화되었습니다.',
      })
    } catch (error) {
      if (error instanceof QueueRedirectError) {
        return
      }

      const message =
        error instanceof Error
          ? error.message
          : '알 수 없는 오류가 발생했습니다.'

      setResetFeedback({ type: 'error', message })
    } finally {
      setIsResetting(false)
    }
  }

  if (isLoading) {
    return (
      <main className="seat-page">
        <p className="seat-page__message">좌석을 불러오는 중입니다...</p>
      </main>
    )
  }

  if (error) {
    return (
      <main className="seat-page">
        <p className="seat-page__message seat-page__message--error">
          {error}
        </p>
      </main>
    )
  }

  return (
    <main className="seat-page">
      <div className="seat-page__actions">
        <button
          type="button"
          className="seat-page__reset-button"
          disabled={isResetting}
          onClick={handleResetSeats}
        >
          {isResetting ? '초기화 중...' : '좌석 예매 초기화'}
        </button>
      </div>

      <header className="seat-page__header">
        <h1>좌석 조회</h1>
        <p>원하는 좌석을 선택해 주세요.</p>
      </header>

      {resetFeedback && (
        <p
          className={`seat-page__feedback seat-page__feedback--${resetFeedback.type}`}
          role={resetFeedback.type === 'error' ? 'alert' : 'status'}
        >
          {resetFeedback.message}
        </p>
      )}

      <div className="seat-page__stage">STAGE</div>

      <section className="seat-grid" aria-label="좌석 목록">
        {seats.map((seat) => (
          <SeatItem
            key={seat.id}
            seatNo={seat.seatNo}
            status={seat.status}
            onClick={() => handleSeatClick(seat)}
          />
        ))}
      </section>

      {seats.length === 0 && (
        <p className="seat-page__message">등록된 좌석이 없습니다.</p>
      )}

      <aside className="seat-legend" aria-label="좌석 상태 안내">
        <div>
          <span className="seat-legend__color seat-legend__color--available" />
          선택 가능
        </div>

        <div>
          <span className="seat-legend__color seat-legend__color--reserved" />
          예약됨
        </div>

        <div>
          <span className="seat-legend__color seat-legend__color--sold" />
          판매 완료
        </div>
      </aside>

    </main>
  )
}

export default SeatPage
