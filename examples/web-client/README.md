# web-client

`traffic-control-system`용 React(Vite) 프론트엔드 프로젝트입니다.

## 실행

```bash
cd examples/web-client
npm install
npm run dev
```

기본 개발 서버는 `http://localhost:5173` 입니다.

## 동작

- 대기열(`/queue/*`)과 API(`/api/*`)를 Vite 개발 서버가 각각 Turnstile과 Gateway로 프록시합니다.
  - `VITE_TURNSTILE_PROXY_TARGET` (기본 `http://localhost:8083`)
  - `VITE_API_PROXY_TARGET` (기본 `http://localhost:8080`)
- 대기열과 API를 잇는 로직은 `src/services/apiClient.ts`에 있습니다.
  - 입장 토큰이 없거나 API가 401을 주면 대기열에 진입하고, 자리가 없으면 `/waiting` 페이지에서 기다립니다.
  - 입장한 동안 토큰을 주기적으로 연장하고, 예매를 마치면 자리를 반납합니다.
