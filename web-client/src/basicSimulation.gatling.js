import {
  bodyString,
  constantUsersPerSec,
  exec,
  getParameter,
  global,
  jmesPath,
  jsonPath,
  regex,
  scenario,
  simulation,
  StringBody,
} from "@gatling.io/core";
import { http, sse, status } from "@gatling.io/http";

const baseUrl = getParameter("baseUrl", "http://localhost:8080");
const usersPerSec = Number(getParameter("usersPerSec", "100"));
const durationSeconds = Number(getParameter("durationSeconds", "60"));
const queueTimeoutSeconds = Number(getParameter("queueTimeoutSeconds", "90"));
const maxFailurePercent = Number(getParameter("maxFailurePercent", "0"));

const SEATS_API = "/api/v1/concerts/seats";
const RESERVATION_API = "/api/v1/reservation";
const CURRENT_PAGE_HEADER = "X-Current-Page-Uri";

const authorizationHeader = (session) =>
  session.contains("accessToken") ? `Bearer ${session.get("accessToken")}` : "";

/*
 * 202 응답 본문에서 Gatling이 연결할 SSE 주소를 꺼낸다.
 */
function saveQueuePath(responseKey, queuePathKey) {
  return exec((session) => {
    try {
      const response = JSON.parse(session.get(responseKey));

      if (!response.queueSsePath) {
        return session.markAsFailed();
      }

      return session.set(queuePathKey, response.queueSsePath);
    } catch {
      return session.markAsFailed();
    }
  });
}

/*
 * waiting 이벤트는 무시하고 allowed 이벤트가 올 때까지 기다린다.
 * 발급받은 JWT는 accessToken에 저장한다.
 */
function waitForQueue(name, streamName, queuePathKey) {
  const allowedEvent = sse
    .checkMessage(`${name} allowed`)
    .matching(jmesPath("event").is("allowed"))
    .check(
      jmesPath("data.status").is("ALLOWED"),
      jmesPath("data.token").saveAs("accessToken"),
    );

  return exec(
    sse(`${name} connect`)
      .sseName(streamName)
      .get((session) => session.get(queuePathKey))
      .disableUrlEncoding()
      .await(queueTimeoutSeconds)
      .on(allowedEvent),

    sse(`${name} close`).sseName(streamName).close(),
  ).exitHereIfFailed();
}

/*
 * 좌석 조회
 *
 * 200: AVAILABLE 좌석 ID 목록 저장
 * 202: 응답 본문을 저장한 뒤 대기열 분기에서 사용
 */
const lookupSeats = http("Seat lookup")
  .get(SEATS_API)
  .header(CURRENT_PAGE_HEADER, "/")
  .check(
    status().in(200, 202).saveAs("seatStatus"),
    bodyString().saveAs("seatResponse"),
    jsonPath("$[?(@.status == 'AVAILABLE')].id")
      .ofLong()
      .findAll()
      .optional()
      .saveAs("availableSeatIds"),
  );

/*
 * 대기열 통과 후 좌석 조회를 동일하게 재시도한다.
 * 새로 받은 JWT를 Authorization 헤더에 넣는다.
 */
const retrySeatLookup = http("Seat lookup after queue")
  .get(SEATS_API)
  .header(CURRENT_PAGE_HEADER, "/")
  .header("Authorization", authorizationHeader)
  .check(
    status().is(200),
    jsonPath("$[?(@.status == 'AVAILABLE')].id")
      .ofLong()
      .findAll()
      .saveAs("availableSeatIds"),
  );

/*
 * 조회된 AVAILABLE 좌석 중 하나를 선택한다.
 * VU ID를 이용해 동일 좌석 선택 가능성을 줄인다.
 */
const chooseSeat = exec((session) => {
  const availableSeatIds = session.get("availableSeatIds");

  if (!Array.isArray(availableSeatIds) || availableSeatIds.length === 0) {
    return session.markAsFailed();
  }

  const index = (session.userId() - 1) % availableSeatIds.length;

  return session.set("seatId", availableSeatIds[index]);
});

const reservationBody = StringBody((session) =>
  JSON.stringify({
    bookerName: session.get("bookerName"),
    seatId: session.get("seatId"),
  }),
);

/*
 * 좌석 예매
 *
 * 조회 단계에서 토큰을 받았다면 재사용한다.
 * 토큰이 없으면 빈 Authorization 헤더가 전달되며 Gateway는 무토큰 요청으로 처리한다.
 *
 * 200: 예약 ID 저장
 * 202: 대기열 응답 본문 저장
 */
const reserveSeat = http("Reserve seat")
  .post(RESERVATION_API)
  .header(
    CURRENT_PAGE_HEADER,
    (session) => `/reservation/${session.get("seatId")}`,
  )
  .header("Authorization", authorizationHeader)
  .body(reservationBody)
  .asJson()
  .check(
    status().in(200, 202).saveAs("reservationStatus"),
    bodyString().saveAs("reservationResponse"),
    regex("^\\d+$").optional().saveAs("reservationId"),
  );

/*
 * 예매 요청에서 대기열에 걸린 경우,
 * 새로 받은 토큰과 동일한 request body로 POST를 재시도한다.
 */
const retryReservation = http("Reserve seat after queue")
  .post(RESERVATION_API)
  .header(
    CURRENT_PAGE_HEADER,
    (session) => `/reservation/${session.get("seatId")}`,
  )
  .header("Authorization", authorizationHeader)
  .body(reservationBody)
  .asJson()
  .check(status().is(200), regex("^\\d+$").saveAs("reservationId"));

/*
 * 실제 사용자 시나리오
 *
 * 좌석 조회
 *   └─ 202 → SSE → 토큰 → 좌석 조회 재시도
 *
 * 좌석 선택
 *
 * 좌석 예매
 *   └─ 202 → SSE → 토큰 → 좌석 예매 재시도
 */
const seatReservationScenario = scenario("Seat reservation")
  .exec((session) =>
    session.set("bookerName", `gatling-user-${session.userId()}`),
  )

  .exec(lookupSeats)
  .exitHereIfFailed()

  .doIf((session) => session.get("seatStatus") === 202)
  .then(
    saveQueuePath("seatResponse", "seatQueuePath"),
    waitForQueue("Seat queue", "seatQueue", "seatQueuePath"),
    retrySeatLookup,
  )
  .exitHereIfFailed()

  .exec(chooseSeat)
  .exitHereIfFailed()

  .exec(reserveSeat)
  .exitHereIfFailed()

  .doIf((session) => session.get("reservationStatus") === 202)
  .then(
    saveQueuePath("reservationResponse", "reservationQueuePath"),
    waitForQueue(
      "Reservation queue",
      "reservationQueue",
      "reservationQueuePath",
    ),
    retryReservation,
  )
  .exitHereIfFailed()

  .exec((session) =>
    session.contains("reservationId") ? session : session.markAsFailed(),
  )
  .exitHereIfFailed();

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
