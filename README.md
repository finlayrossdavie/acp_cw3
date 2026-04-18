# CW3 Election Data PoC Baseline

This project implements the CW3 must-have baseline: election data ingestion, projection precompute, DynamoDB storage via LocalStack, Redis-cached API, and a React map UI for NC, GA, ME, MI, and TX.

## Stack
- Backend: Spring Boot (Java 17, Maven)
- Frontend: React + Vite
- Infra: Docker Compose, LocalStack (DynamoDB), Redis
- Data: FiveThirtyEight poll feed + NewsAPI + fallback JSON

## Quick Start
1. Copy `.env.example` to `.env` and set `NEWS_API_KEY`.
2. Run:
   - `docker compose up --build`
3. Open:
   - Frontend: `http://localhost:5173`
   - Backend API: `http://localhost:8080`

## API Endpoints
- `GET /states` -> state summaries (`state`, `leadingParty`, `margin`, `color`, `updatedAt`)
- `GET /state/{code}` -> detailed race data
- `GET /race/{id}` -> race by id
- `POST /admin/refresh` -> internal refresh trigger (poll+news ingestion, recompute, DynamoDB upsert, cache invalidation)

## Data Pipeline Behavior
1. Backend starts and runs ingestion.
2. It tries live ingestion from FiveThirtyEight and NewsAPI.
3. If live fails, it loads fallback data from `backend/src/main/resources/fallback/races.json`.
4. Projections are computed during ingestion and persisted.
5. API reads from Redis cache first, then DynamoDB.

## Cache Design
- Keys:
  - `states:summary`
  - `state:{code}`
  - `race:{id}`
- TTL: 300 seconds
- Invalidation on refresh:
  - `states:*`
  - `state:*`
  - `race:*`

## Verification Checklist
- `GET /states` returns exactly NC, GA, ME, MI, TX.
- DynamoDB table `ElectionRaces` exists in LocalStack and contains 5 race objects.
- First `GET /states` populates Redis cache, second call is a cache hit.
- `POST /admin/refresh` logs `Using LIVE data` or `Using FALLBACK data`.
- Map state colors match backend `color` field.

## Tests
Backend tests are under `backend/src/test/java` and include:
- `ProjectionServiceTest`
- `IngestionServiceTest`
- `StateControllerTest`
- `RaceControllerTest`

If Maven is installed locally:
- `cd backend`
- `mvn test`
