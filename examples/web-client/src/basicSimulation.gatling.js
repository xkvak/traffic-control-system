import {
  asLongAsDuring,
  bodyString,
  constantUsersPerSec,
  exec,
  getParameter,
  global,
  jmesPath,
  pause,
  regex,
  scenario,
  simulation,
  StringBody,
} from "@gatling.io/core";
import { http, status } from "@gatling.io/http";

const baseUrl = getParameter("baseUrl", "http://localhost:8080");
const queueBaseUrl = getParameter("queueBaseUrl", "http://localhost:8083");
const usersPerSec = Number(getParameter("usersPerSec", "500"));
const durationSeconds = Number(getParameter("durationSeconds", "300"));
const queueTimeoutSeconds = Number(getParameter("queueTimeoutSeconds", "120"));
const maxFailurePercent = Number(getParameter("maxFailurePercent", "0"));

const SEATS_API = "/api/v1/concerts/seats";
const RESERVATION_API = "/api/v1/reservation";

const authorization = (session) => `Bearer ${session.get("accessToken")}`;

const queueChecks = [
  status().is(200),
  jmesPath("status").saveAs("queueStatus"),
  jmesPath("token").optional().saveAs("accessToken"),
  jmesPath("retryAfterMs").optional().saveAs("retryAfterMs"),
];

const enterQueue = http("Queue enter")
  .post(`${queueBaseUrl}/queue/enter`)
  .check(...queueChecks);

const pollQueue = http("Queue status")
  .get(`${queueBaseUrl}/queue/status`)
  .check(...queueChecks);

const waitInQueue = exec(
  asLongAsDuring(
    (session) => session.get("queueStatus") === "WAITING",
    queueTimeoutSeconds,
  ).on(
    pause((session) => ({
      amount: Number(session.get("retryAfterMs") ?? 1000),
      unit: "milliseconds",
    })),
    pollQueue,
  ),
  exec((session) =>
    session.get("queueStatus") === "ALLOWED" ? session : session.markAsFailed(),
  ),
).exitHereIfFailed();

const lookupSeats = http("Seat lookup")
  .get(SEATS_API)
  .header("Authorization", authorization)
  .check(status().is(200), bodyString().saveAs("seatResponse"));

const extractSeatAvailability = exec((session) => {
  try {
    const seats = JSON.parse(session.get("seatResponse"));

    if (!Array.isArray(seats)) {
      return session.markAsFailed();
    }

    const availableSeatIds = seats
      .filter((seat) => seat?.status === "AVAILABLE")
      .map((seat) => seat.id);

    if (availableSeatIds.some((seatId) => !Number.isInteger(seatId))) {
      return session.markAsFailed();
    }

    return session
      .set("availableSeatIds", availableSeatIds)
      .set("soldOut", availableSeatIds.length === 0);
  } catch {
    return session.markAsFailed();
  }
});

const chooseSeat = exec((session) => {
  const availableSeatIds = session.get("availableSeatIds");

  if (!Array.isArray(availableSeatIds) || availableSeatIds.length === 0) {
    return session.markAsFailed();
  }

  const index = (session.userId() - 1) % availableSeatIds.length;

  return session.set("seatId", availableSeatIds[index]);
});

const reserveSeat = http("Reserve seat")
  .post(RESERVATION_API)
  .header("Authorization", authorization)
  .body(
    StringBody((session) =>
      JSON.stringify({
        bookerName: session.get("bookerName"),
        seatId: session.get("seatId"),
      }),
    ),
  )
  .asJson()
  .check(status().is(200), regex("^\\d+$").saveAs("reservationId"));

const leaveQueue = http("Queue leave")
  .post(`${queueBaseUrl}/queue/leave`)
  .header("Authorization", authorization)
  .check(status().is(204));

const seatReservationScenario = scenario("Seat reservation")
  .exec((session) =>
    session.set("bookerName", `gatling-user-${session.userId()}`),
  )
  .exec(enterQueue)
  .exitHereIfFailed()
  .exec(waitInQueue)
  .exec(lookupSeats)
  .exitHereIfFailed()
  .exec(extractSeatAvailability)
  .exitHereIfFailed()
  .exitHereIf((session) => session.get("soldOut") === true)
  .exec(chooseSeat)
  .exitHereIfFailed()
  .exec(reserveSeat)
  .exec(leaveQueue);

export default simulation((setUp) => {
  const httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .userAgentHeader("gatling-seat-reservation-test/1.0");

  setUp(
    seatReservationScenario.injectOpen(
      constantUsersPerSec(usersPerSec).during(durationSeconds),
    ),
  )
    .protocols(httpProtocol)
    .assertions(global().failedRequests().percent().lte(maxFailurePercent));
});
