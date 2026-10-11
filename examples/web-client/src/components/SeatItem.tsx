export type SeatStatus = 'AVAILABLE' | 'RESERVED' | 'SOLD'

type SeatItemProps = {
  seatNo: string
  status: SeatStatus
  onClick?: () => void
}

function SeatItem({ seatNo, status, onClick }: SeatItemProps) {
  const isAvailable = status === 'AVAILABLE'

  return (
    <button
      type="button"
      className={`seat-item seat-item--${status.toLowerCase()}`}
      disabled={!isAvailable}
      onClick={onClick}
      aria-label={`${seatNo} 좌석, ${status}`}
    >
      <span className="seat-item__shape" aria-hidden="true" />
      <span className="seat-item__number">{seatNo}</span>
    </button>
  )
}

export default SeatItem
