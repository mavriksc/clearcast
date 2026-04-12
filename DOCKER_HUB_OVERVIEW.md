# Clearcast Docker Hub Overview

Clearcast is a self-hosted Kotlin weather dashboard that serves a local web UI backed by public U.S. weather data sources.

## Highlights

- current conditions, hourly forecast, and multi-day forecast
- watches, warnings, and advisories
- animated radar with graceful fallback behavior
- simple `.env`-based configuration
- no database required

## Quick Start

```bash
docker run --rm \
  -p 8080:8080 \
  --env-file .env \
  -v $(pwd)/data:/app/data \
  clearcast:latest
```

Then open `http://localhost:8080`.

## Required Configuration

Set one of the following:

- `ZIP_CODE`
- `LAT` and `LON`

Also set:

- `NWS_USER_AGENT=Clearcast (contact:you@example.com)`

## Volumes

- `/app/data` for cached weather and lookup data

## Source

Use `config.example` as the starting point for your `.env` file.
