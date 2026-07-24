# Graph Report - D:\code\clearcast  (2026-07-14)

## Corpus Check
- Corpus is ~11,837 words - fits in a single context window. You may not need a graph.

## Summary
- 189 nodes · 397 edges · 15 communities (13 shown, 2 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 57 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]

## God Nodes (most connected - your core abstractions)
1. `WeatherService` - 36 edges
2. `WeatherGovParsersTest` - 25 edges
3. `RadarWmsService` - 24 edges
4. `WeatherGovService` - 17 edges
5. `loadEnv()` - 15 edges
6. `parseDailyForecast()` - 13 edges
7. `configureControllers()` - 12 edges
8. `parseHourlyForecast()` - 12 edges
9. `parseCurrentFromHourlyJson()` - 12 edges
10. `parseAlerts()` - 12 edges

## Surprising Connections (you probably didn't know these)
- `loadAlertFilters()` --calls--> `loadEnv()`  [INFERRED]
  src/main/kotlin/org/mavriksc/clearcast/AlertFilters.kt → src/main/kotlin/org/mavriksc/clearcast/Env.kt
- `configureControllers()` --calls--> `CurrentConditionsCard`  [INFERRED]
  src/main/kotlin/org/mavriksc/clearcast/Controllers.kt → src/main/kotlin/org/mavriksc/clearcast/Types.kt
- `module()` --calls--> `configureControllers()`  [INFERRED]
  src/main/kotlin/org/mavriksc/clearcast/Main.kt → src/main/kotlin/org/mavriksc/clearcast/Controllers.kt
- `main()` --calls--> `loadEnv()`  [INFERRED]
  src/main/kotlin/org/mavriksc/clearcast/services/RadarRidgeService.kt → src/main/kotlin/org/mavriksc/clearcast/Env.kt
- `fromEnv()` --calls--> `loadEnv()`  [INFERRED]
  src/main/kotlin/org/mavriksc/clearcast/services/RadarWmsService.kt → src/main/kotlin/org/mavriksc/clearcast/Env.kt

## Communities (15 total, 2 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.17
Nodes (15): CachedAlertsCard, CachedCurrentConditionsCard, CachedDailyForecastCard, CachedDailyForecastPoint, CachedHourlyForecastCard, CachedHourlyForecastPoint, CachedNextFetchTimes, CachedRadarCard (+7 more)

### Community 1 - "Community 1"
Cohesion: 0.14
Nodes (4): fromEnv(), GifSequenceWriter, RadarWmsConfig, RadarWmsService

### Community 2 - "Community 2"
Cohesion: 0.19
Nodes (26): AlertsCard, CurrentConditionsCard, WeatherAlert, array(), DailyPeriod, inferConditionTheme(), jsonPrimitive(), num() (+18 more)

### Community 3 - "Community 3"
Cohesion: 0.14
Nodes (15): clearCacheOnStartupIfRequested(), deleteDirectoryIfExists(), ensureGazetteerZip(), ensureLatLonFromZip(), findEnvFileOrRoot(), findGazetteerColumns(), findZipInGazetteer(), loadEnv() (+7 more)

### Community 4 - "Community 4"
Cohesion: 0.24
Nodes (10): ensureGazetteerZip(), findGazetteerColumns(), findZipInGazetteer(), fromEnv(), main(), parseLatLon(), resolveLatLonFromZip(), updateEnvLatLon() (+2 more)

### Community 5 - "Community 5"
Cohesion: 0.14
Nodes (12): AlertSeverity, AlertUrgency, ConditionTheme, DailyForecastCard, DailyForecastPoint, HourlyForecastCard, HourlyForecastPoint, MoonPhase (+4 more)

### Community 6 - "Community 6"
Cohesion: 0.32
Nodes (11): buildDailyLabels(), buildEstimatedTempText(), buildHourlyPrecipList(), buildHourlyTempsList(), buildHourlyTimesList(), buildHourlyYMin(), buildRadarFrameUrls(), conditionEmoji() (+3 more)

### Community 7 - "Community 7"
Cohesion: 0.20
Nodes (8): chart, dataEl, layout, observer, precipTrace, tempTrace, tickStep, tickVals

### Community 8 - "Community 8"
Cohesion: 0.44
Nodes (4): buildClient(), main(), RadarRidgeService, resolveStation()

### Community 9 - "Community 9"
Cohesion: 0.36
Nodes (3): AlertFilters, loadAlertFilters(), parseFilterList()

## Knowledge Gaps
- **17 isolated node(s):** `RadarMode`, `PrecipType`, `AlertSeverity`, `AlertUrgency`, `ConditionTheme` (+12 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `loadEnv()` connect `Community 3` to `Community 0`, `Community 1`, `Community 4`, `Community 8`, `Community 9`?**
  _High betweenness centrality (0.414) - this node is a cross-community bridge._
- **Why does `fromEnv()` connect `Community 1` to `Community 3`?**
  _High betweenness centrality (0.220) - this node is a cross-community bridge._
- **Are the 12 inferred relationships involving `loadEnv()` (e.g. with `loadAlertFilters()` and `loadRefreshConfig()`) actually correct?**
  _`loadEnv()` has 12 INFERRED edges - model-reasoned connections that need verification._
- **What connects `RadarMode`, `PrecipType`, `AlertSeverity` to the rest of the system?**
  _17 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.1402116402116402 - nodes in this community are weakly interconnected._
- **Should `Community 3` be split into smaller, more focused modules?**
  _Cohesion score 0.13666666666666666 - nodes in this community are weakly interconnected._
- **Should `Community 5` be split into smaller, more focused modules?**
  _Cohesion score 0.14285714285714285 - nodes in this community are weakly interconnected._