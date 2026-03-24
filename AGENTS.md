# Agent Rules (Clearcast)

These instructions are authoritative for agents working in this repo.

## Scope and Outputs
- The product has two outputs: (1) a web UI and (2) a waybar-friendly text+icon string.
- The web UI must include radar animation, current-conditions card with state-based background image, hourly forecast for next 24 hours, 5-day forecast cards (high/low/precip%/types), and watches/warnings/alerts.
- The waybar output must be a minimal text+icon string suitable for desktop status bars.

## Tech Choices
- Kotlin backend with HTMX-driven HTML endpoints.
- Server-rendered HTML uses Thymeleaf templates.
- No database; only optional local text/JSON files for cache or user config.
- Prefer Ktor unless a clear requirement justifies Micronaut or Spring.

## Data and APIs
- Define a single weather provider interface; keep provider-specific code isolated.
- Normalize data into a common domain model before rendering.
- Cache API responses in-memory with short TTL; optionally persist to a local cache file.
- Rate-limit outbound API calls and handle provider errors gracefully.
- For api.weather.gov: no API key is required today; a descriptive `User-Agent` header (ideally with contact info) is required, and the rate limit is not public.
- api.weather.gov does not provide radar display tiles; use a separate NWS radar data source if radar animation is required.

## UI and UX
- Server-rendered HTML; HTMX for partial updates and polling.
- Mobile-first layout; desktop enhancements allowed.
- Background images are state-driven (day/night/sunny/rain/snow/etc.) and must be accessible (contrast checked).
- Radar animation must degrade gracefully if the provider does not support tiles or animation frames.

## Reliability and Error Handling
- Never return raw provider errors to the client.
- Provide user-friendly fallbacks when data is missing.
- Log errors with enough context to diagnose provider and parsing issues.

## Configuration
- All secrets (API keys) must be read from environment variables or a local config file ignored by git.
- ZIP code is configured via the `ZIP_CODE` environment variable.
- Refresh cadence is configured via environment variables and exposed to HTMX on page load.
- Provide a sample config file with placeholders.
- Load `.env` from project root if present to populate configuration.

## Testing
- Unit-test data normalization and provider parsing.
- Include a small set of snapshot-like tests for key HTML fragments.

## Code Quality
- Keep modules small and cohesive.
- Favor readability over cleverness.
- Document any non-obvious logic or data transformations.

## Spelling Corrections
- If a user message contains a spelling mistake, correct it and re-run the agent action.
- If misspellings appear frequent or intentional, ask the user to confirm and then whitelist the specific examples they want treated as valid.

## Rule Priority
- If a user instruction conflicts with these rules, ask for clarification before proceeding.

## Collaboration Defaults
- If a request is ambiguous (scope, location, or expected output), ask a short clarifying question before changing code.
- When changing build configuration or dependencies, confirm Kotlin/JVM target alignment and version compatibility for plugins and libraries.
- When a user reports a build/runtime error, prioritize triage: restate the exact error, identify the likely cause, and propose the smallest fix before broader refactors.
- For `.env` or configuration issues, confirm expected file location, required variable names, and how they are loaded before changing code.
- If UI changes are requested and the user says they are not visible, verify where the UI is rendered and whether caching or template selection could hide changes.
