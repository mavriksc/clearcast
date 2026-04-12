# Clearcast

Clearcast is a Kotlin weather dashboard for local self-hosting. It pulls forecast, radar, and alert data from U.S. public weather sources and renders a server-side web UI plus a compact status-style output.

## What It Is

Clearcast is built around a small Ktor backend with Thymeleaf templates and HTMX-driven partial updates. The app is intended to stay simple to run:

- current conditions with state-driven presentation
- hourly forecast for the next 24 hours
- multi-day forecast cards
- active watches, warnings, and advisories
- animated radar with graceful fallback behavior
- optional ZIP-based startup location lookup

## Usage

### Local run

1. Copy `config.example` to `.env`.
2. Set either `ZIP_CODE` or both `LAT` and `LON`.
3. Set `NWS_USER_AGENT` to a descriptive value with contact info.
4. Start the app:

```powershell
.\gradlew run
```

Open [http://localhost:8080](http://localhost:8080).

### Docker run

Build and run with Compose:

```powershell
docker compose up --build
```

Or build the image directly:

```powershell
docker build -t clearcast:latest .
docker run --rm -p 8080:8080 --env-file .env -v ${PWD}\data:/app/data clearcast:latest
```

The container stores cache files under `/app/data`, and the included Compose file maps that to the local `data/` directory.

## Configuration

The main runtime settings are:

- `ZIP_CODE`, `LAT`, `LON` for location selection
- `NWS_USER_AGENT` for `api.weather.gov` requests
- `RADAR_PROVIDER` for radar source selection
- `REFRESH_*` values for polling cadence
- `ALERT_FILTER` and `ALERT_FILTER_INSENSITIVE` for basic alert filtering
- `CLEAR_CACHE=true` to wipe cached files on startup

See [config.example](./config.example) for the full example configuration.

## Notes

- No database is required.
- Cached provider responses are stored under `data/` and `responses/`.
- Radar behavior depends on upstream public services and may fall back when a source is unavailable.

## Future Work

- Improve alert processing beyond basic text filtering, including better deduplication, grouping, and severity-aware presentation.
