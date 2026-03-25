# Clearcast Weather App Plan 🗺️

## Goals 🎯
Build a Kotlin + HTMX weather app with two outputs:
- Web interface with radar animation, current-conditions card with state-based background imagery, hourly forecast (next 24h), 5-day forecast cards (high/low/precip%/types), and watches/warnings/alerts.
- Waybar output: a compact text + icon string using Nerd Font and Noto Sans.

## Framework Decision 🧱
Choose **Ktor**.
- It is the lightest-weight option, aligns well with simple server-rendered HTML + HTMX endpoints, and keeps configuration minimal.
- Micronaut and Spring are excellent but bring more framework overhead than needed for a small, file-backed app.

## Architecture 🏗️
- **Backend**: Ktor server, HTML rendering, HTMX endpoints for partial updates (current conditions, hourly, alerts, radar).
- **Frontend**: Server-rendered HTML with HTMX for polling and partial refreshes. Server provides next poll times on initial render; HTMX uses `hx-trigger` with `delay` or `every` based on env-configured cadence.
- **Data**: A provider module fetches weather/radar/alerts; results normalized into a domain model.
- **Cache**: In-memory with TTL; optional file cache (JSON) for last-known data and offline fallbacks.
- **Waybar**: A small endpoint or CLI mode returning `ICON TEMP CONDITION` (and optional alerts badge).

## Key User Flows 🚦
1. Open web UI: server renders a full page with last-known data; HTMX triggers refresh.
2. Radar tile animation: user sees looped frames or provider animation; falls back to static tile if needed.
3. Hourly and 5-day cards update on a schedule without full reload.
4. Waybar polls a minimal endpoint or runs a CLI to emit text+icon.

## Data Model (Normalized) 🧾
- Location: name, lat/lon, timezone.
- Current: temp, feels-like, humidity, wind, condition, icon, day/night.
- Hourly: list of 24 points (time, temp, precip%, type, icon).
- Daily: list of 5 days (date, high/low, precip%, types, icon).
- Alerts: list of active watches/warnings/advisories.
- Radar: tile URL template or frame URLs + timestamps.

## Pages and Endpoints 🔗
- `/` full page render.
- `/partials/current`
- `/partials/hourly`
- `/partials/daily`
- `/partials/alerts`
- `/partials/radar`
- `/waybar` (plain text + icon)

## Visual System 🎨
- Use `0xProto Nerd Font Mono` for monospace UI bits and icons; `Noto Sans` for body text.
- Background imagery mapped to condition + day/night.
- Color and contrast meet accessibility minimums.

## Condition Themes 🧩
- `ConditionTheme` enum powers theme art selection; day/night is handled separately.
- The NWS `shortForecast` field is treated as free text; we map keywords to themes.
- Examples from current responses:
  - Clear, Mostly Clear → `CLEAR`
  - Sunny, Mostly Sunny → `SUNNY`
  - Partly Cloudy, Partly Sunny → `PARTLY_CLOUDY`
  - Mostly Cloudy, Cloudy, Overcast → `CLOUDY`
  - Rain, Showers → `RAIN`
  - Snow → `SNOW`
  - Thunderstorms → `STORM`
  - Fog, Mist → `FOG`
  - Windy, Breezy → `WINDY`
  - Haze, Smoke → `HAZE`

## Config and Secrets 🔐
- `.env` or local config file (ignored by git) with provider API keys.
- `config.example` checked in with placeholders.
- `ZIP_CODE` sets the default location.
- `REFRESH_CURRENT_SEC`, `REFRESH_RADAR_SEC`, `REFRESH_DAILY_SEC`, `REFRESH_ALERTS_SEC` define polling cadence exposed to HTMX on page load.
- `DELAY_FOR_RENDER` defines the initial HTMX delay before first refresh.
- HTML templates use Thymeleaf (Ktor plugin).

## Testing 🧪
- Unit tests for provider parsing and normalization.
- Basic HTML fragment tests for current/hourly/daily rendering.

## Implementation Steps ✅
1. Ktor skeleton with routing, templating, and static assets. âœ…
2. Provider interface + a first weather provider implementation. âœ…
3. Domain normalization + caching layer. âœ…
4. HTML templates and HTMX endpoints.
5. Radar animation integration and graceful fallbacks. âœ…
6. Waybar endpoint/CLI (covered by `/api/current-emoji` for now). âœ…
7. Sample config. âœ…

## Open Decisions ❓
- Select initial weather/radar provider and confirm data licensing.
- Decide whether waybar output is via HTTP endpoint or CLI script (covered by `/api/current-emoji` for now).
- Confirm radar source for past frames and whether "future radar" means nowcast data from a different provider.
- Decide on optional cloud/satellite overlays and placement (radar overlay vs separate satellite card).
- Optional cloud/satellite data sources to evaluate: NOAA nowCOAST GOES WMS; NASA GOES GIS WMS.

## Provider Notes (api.weather.gov) 📡
- No API key required today; a descriptive `User-Agent` is required and should include contact info.
- Rate limit is not public; typical use is allowed but clients must back off on limit errors.
- api.weather.gov does not provide radar display tiles; use a separate NWS radar display/data source for animation.

## Refresh Policy ⏱️
- Current conditions: as often as allowed by rate limits.
- Radar: past/near-term animation refreshed hourly.
- Hourly + 5-day forecasts and alerts: refreshed daily per requirement.
