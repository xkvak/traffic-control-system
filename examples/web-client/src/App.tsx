import { Navigate, Route, Routes } from 'react-router-dom'
import QueuePage from './pages/QueuePage'
import ReservationPage from './pages/ReservationPage'
import SeatPage from './pages/SeatPage'

function App() {
  return (
    <Routes>
      <Route path="/" element={<SeatPage />} />
      <Route path="/queue" element={<QueuePage />} />
      <Route path="/reservation/:seatId" element={<ReservationPage />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
