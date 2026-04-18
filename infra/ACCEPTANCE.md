# Post-deploy acceptance checklist

Run against the **CloudFront URL** (`https://<id>.cloudfront.net`) with the API reachable at **`VITE_API_BASE_URL`**.

| Check | How |
|-------|-----|
| Health | `GET {API}/actuator/health` returns 200 |
| States list | `GET {API}/states` returns JSON |
| CORS | Browser devtools: no CORS errors when loading the map from CloudFront |
| Map | All color modes: Polls, Market, Current Seat, Spending |
| State detail | Polls, Betting (Polymarket + Kalshi), News, Finance |
| Admin refresh | `POST {API}/admin/refresh` (protect in production) |

If news or finance is empty, confirm API keys are set on the ECS task and outbound HTTPS from Fargate works (security groups / public IP).
