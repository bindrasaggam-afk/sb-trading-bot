# SB (SBA) प्रोजेक्ट — अपडेटेड A-to-Z रिपोर्ट

**अपडेट की तारीख:** 21 नवंबर 2026 (शनिवार)
**प्रोजेक्ट:** SB Trading Bot — Android / Kotlin ऐप + लोकल मार्केट-डेटा और ट्रेडिंग बैकएंड
**प्रोजेक्ट पाथ:** `/storage/internal_new/project/sbApp`
**ब्रांच:** `feature/platform-hardening`
**इस रन का दायरा:** सिर्फ वे काम जो इस मोबाइल एनवायरनमेंट में चल सकते हैं — compile, JVM unit tests, `assembleDebug`, Git cleanup/commit और दस्तावेज़। लाइव ADB / emulator / Shizuku / `rish` वाले UI/instrumented टेस्ट **नहीं चलाए गए** और लैपटॉप के लिए पेंडिंग हैं।

**मुख्य परिणाम:** `./gradlew test assembleDebug --offline` सफल — Debug 291/291 और Release 291/291 JVM unit tests पास (0 fail, 0 skip); APK 15 MB; 7 लॉजिकल commits (पिछले 6 + इस रन का chart persistence/config commit); Git working tree इस रिपोर्ट के अंतिम commit के बाद साफ़।

**स्थिति सारांश:**

| # | काम | स्थिति |
|---|---|---|
| 1 | Deterministic candle engine + session-aware pipeline | **कंप्लीट** |
| 2 | Repository cleanup (`.hprof`, जंक फ़ाइलें) + logical commits | **कंप्लीट** |
| 3 | दोनों DB अलग-अलग, प्रॉपर init + coordinated facade/shutdown | **कंप्लीट** |
| 4 | DB backup/restore hardening — JVM safety layer + owner-scoped tests | **कंप्लीट (JVM स्तर)** |
| 5 | `1m→5m→15m→1h` + chart↔DB cross-check — JVM fixtures | **कंप्लीट (JVM स्तर)** |
| 6 | `./gradlew test` (291/291 × 2) + `assembleDebug` (15 MB APK) | **कंप्लीट** |
| 7 | Shizuku (rish) ताज़ा जाँच — बंद/अनुपलब्ध, इस्तेमाल संभव नहीं | **कंप्लीट (निरीक्षण)** |
| 8 | Drawing persistence + configurable indicators — versioned pure-JVM layer, UI hooks | **कंप्लीट (JVM स्तर)** |
| 9 | लैपटॉप/फ़ोन पर लाइव UI + instrumented टेस्ट | **पेंडिंग** |
| 10 | Backup gate coverage, independent historical bars, live cross-check samples | **पेंडिंग** |
| 11 | नए indicators (Ichimoku/ADX/...), price alerts, PNG export + प्लेटफ़ॉर्म स्केल/सिक्योरिटी | **पेंडिंग** |

---

# सेक्शन 1 — कंप्लीट वाला वर्क (Completed Work)

> जो काम इस रन में पूरे हुए और वेरिफ़ाई किए गए।

## 1.1 फेलिंग टेस्ट को deterministic बनाया — सबसे बड़ा ब्लॉकर हटा

**समस्या (पहले):** `CandleEngineTest.futureSeededCandleIsIgnoredAndDoesNotBlockLiveTicks` सिस्टम क्लॉक (`System.currentTimeMillis()`) पर निर्भर था। चूंकि 21 नवंबर शनिवार है, `ExchangeSessionCalendar.isTradingTime()` शनिवार/रविवार को टिक reject करती थी और टेस्ट `assertTrue(result.accepted)` पर फेल होता था (`java.lang.AssertionError`, लाइन 258)।

**किया गया बदलाव:**

1. `app/src/main/java/com/sb/backend/market/engine/CandleEngine.kt`
   - `CandleEngine` अब injectable clock लेता है:
     ```kotlin
     class CandleEngine(
         private val clock: () -> Long = { System.currentTimeMillis() }
     )
     ```
   - `seed()` में `val now = clock()`; `eventTimeMillis()` fallback भी `?: clock()`।
   - **Production व्यवहार अपरिवर्तित** — default clock वही सिस्टम टाइम है; सिर्फ टेस्ट में stub लगाया जा सकता है।

2. `app/src/test/java/com/sb/backend/market/engine/CandleEngineTest.kt`
   - fixed weekday मार्केट टाइम और fixed clock: `val now = time(9, 15, 0)`, `CandleEngine { now }`, `future = now + 24h`।
   - अब न सिस्टम क्लॉक पर निर्भरता है, न weekend-आधारित फ्लेकिनेस।

## 1.2 रिपोजिटरी और Git स्टेट साफ़ + लॉजिकल commits

- `.gitignore` में `*.hprof` जोड़ा गया।
- 843 MB की `java_pid19063.hprof`, `tatus --short` और `tory needs investigation"` रिपोजिटरी से हटाई गईं।
- पुराना review artifact `SB_IMPLEMENTATION_REVIEW.patch` ریپो के बाहर `/data/user/0/com.m4coding.ide/cache/opencode/` में सुरक्षित रखा गया (डिलीट नहीं) — ताकि Git status साफ़ रहे और फ़ाइल भी न खोए।
- सभी बदलाव 5 लॉजिकल commits में बाँटे गए:

| Commit | Hash | सामग्री |
|---|---|---|
| 1 | `f863663` | `chore: ignore heap dumps and keep repository clean` |
| 2 | `4d6af51` | `feat(market): make candle pipeline deterministic and session aware` — 14 फ़ाइलें (engine, session calendar, repos, services, डेटा/इंस्ट्रूमेंटेड टेस्ट) |
| 3 | `49c3c87` | `feat(chart): harden chart data boundary and incremental indicators` — 13 फ़ाइलें (chart core, indicator cache, टेस्ट, accuracy doc) |
| 4 | `afb5d1f` | `feat(backend): coordinate two database backends and ordered shutdown` — 8 फ़ाइलें (coordinator, shutdown sequence, API server, lifecycle, signals platform, नया टेस्ट) |
| 5 | `1a95b70` | `docs: refresh Hindi report for dual-database design and verification` |
| 6 | `c5c21a5` | `feat(db): harden owner-scoped backup and accuracy cross-checks` — backup policy/engine, signals-scoped backup, pure aggregator/row-mapper, 15 नए JVM टेस्ट, docs + ये रिपोर्ट |
| 7 | अंतिम | `feat(chart): persist drawings and configure indicators` — drawing/config models, codecs, repositories, non-breaking view/cache hooks, 23 नए JVM टेस्ट, docs + ये रिपोर्ट |

- Commit से पहले `git status`, `git diff`, `git diff --cached` देखे गए; secrets/keystore/shizuku binaries/kotlin compiled artifacts कभी stage नहीं किए गए।

## 1.3 दोनों डेटाबेस — जानबूझकर अलग, पर ऐप में समन्वित

**मुख्य सिद्धांत:** दोनों DB को **न खत्म किया गया, न मर्ज/एक किया गया**। हर DB अपने मॉड्यूल के नियम, स्कीमा-वर्ज़न और lifecycle के साथ अपने owner द्वारा ही खोला/बंद किया जाता है। ऐप स्तर पर सिर्फ **coordination** जोड़ा गया है।

### 1.3.1 अलग-अलग DB का नक्शा (कोई मर्ज नहीं)

| DB फ़ाइल | Owner मॉड्यूल | Helper / Schema | Version | Status |
|---|---|---|---|---|
| `sb_backend.db` | `com.sb.backend.core.BackendManager` (Kotlin `object`) | `SBDatabaseHelper` + `DatabaseMigrations` | `DATABASE_VERSION = 11` | लाइव; instruments, quotes, candles, signals, paper trading, research |
| `sb_signals.db` | `com.sb.signals.services.SignalsPlatform` (Kotlin `object`) | `SignalDatabase` + `SignalMigrations` | `VERSION = 4` | लाइव; strategy versions, signals, events, runs |

**ऐतिहासिक दूसरा बैकएंड (सुरक्षित, अपरिवर्तित):** `com.sb.backend.services.BackendManager`, `BackendDatabase`, `BackendSchema` (v1) और `BackendMigration` (v5) `DELETED_CODE_ARCHIVE.txt` और `backups/` में मौजूद हैं। ये **कंपाइल नहीं होते**, लेकिन **हटाए या मर्ज भी नहीं गए** — जानबूझकर आर्काइव रखे गए हैं। अगर कभी वापस लाएँ तो इसे तीसरे DB के रूप में अपना helper/lifecycle देकर लाना होगा, `sb_backend.db` या `sb_signals.db` में मिलाना नहीं।

### 1.3.2 प्रॉपर initialization

- `BackendRuntimeCoordinator.initialize(context)` (नई फ़ाइल `app/src/main/java/com/sb/backend/core/BackendRuntimeCoordinator.kt`) सिर्फ **दोनों owners को बुलाती है**, स्वयं कोई DB handle नहीं रखती:
  1. `BackendManager.initialize(appContext)` → `SBDatabaseHelper(sb_backend.db)` v11, repositories, services, single-thread executor; idempotent।
  2. `SignalsPlatform.initialize(appContext)` (नया public तरीका) → `SignalDatabase(sb_signals.db)` v4, `SignalRepository`, `SignalAnalysisService`; idempotent।
- `MarketForegroundService.startBackendRuntime()` अब सीधे `BackendManager` को नहीं, coordinator को बुलाता है; start क्रम वही रखा गया: API server → coordinator.start → `BackendManager.start` → onReady में Kotak init, `MarketRuntimeService.start()`, `SignalsPlatform.start()`, `KotakSessionCoordinator.startIfEnabled()`।
- `SBApplication.onCreate()` में पहले से मौजूद `SignalsPlatform.scheduleBackgroundObservation()` वही रहता है; WorkManager के रास्ते भी signals DB अपने owner से ही initialize होते हैं।

### 1.3.3 समन्वित शटडाउन — स्पष्ट क्रम

नई फ़ाइलें:
- `app/src/main/java/com/sb/backend/core/BackendShutdownSequence.kt` — एक शुद्ध-JVM sequence: incoming रोकें → producers drain करें → gate लेकर दोनों DB बंद करें → errors इकट्ठा करें; idempotent।
- `app/src/main/java/com/sb/backend/core/BackendRuntimeCoordinator.kt` — sequence को producers/DB closers देता है; `stopIncoming` कॉलबैक service से आता है।

`MarketForegroundService.onDestroy()` का क्रम:

1. `runtimeState = STOPPED` (नए start/admission रोकें)।
2. `BackendRuntimeCoordinator.shutdown { stopApiServer() }` — सबसे पहले incoming API बंद।
3. **Producers (reverse order), हर एक drain/await सहित:**
   - `SignalsPlatform.stop()` — `accepting=false`, scheduler + worker `shutdownNow()` + 5s await; `runAnalysis()` अब `DatabaseMaintenanceGate.withOperation` के अंदर चलता है।
   - `KotakSessionCoordinator.shutdown()` — feed/autologin बंद, दोनों executors 5s await।
   - `SignalScanCoordinator.shutdown()` — नया तरीका; worker 5s await; अगली `scan()` पर executor फिर से बनता है (restart-safe)।
   - `MarketRuntimeService.shutdown()` — tick listener हटाना + executor 10s await।
   - `MarketDataManager.shutdown()` — snapshot job cancel।
4. `DatabaseMaintenanceGate.shared.acquireMaintenance()` — सभी admitted DB operations के drain होने का इंतज़ार; ये gate API dispatch (`EmbeddedApiServer.dispatchGuarded`), backend repositories, backup और अब signals analysis को कवर करता है।
5. **दोनों DB अपने-अपने owner से बंद:**
   - `BackendManager.stop()` → `sb_backend.db` (`dbHelper.close()`)।
   - `SignalsPlatform.closeDatabase()` (नया) → `sb_signals.db` (`SignalDatabase.close()`), refs clear।
6. gate lease release; service state reset; `runtimeExecutor.shutdownNow()`।

`BackendManager.stop()` का अंदरूनी क्रम भी स्पष्ट किया: `ready/started=false` → `paperTradeMonitor.stop()` → worker `shutdown()` + 10s await → `marketDataService.shutdown()` → `dbHelper.close()` → flags/context reset। `restore()`अब उसी `stop()` को reuse करता है (duplicated teardown हटा)।

`EmbeddedApiServer.stop()` को भी मज़बूत किया: `running=false` → listener socket close → सभी accepted sockets close → accept-executor drain (5s) → worker-executor drain (10s) → active socket set clear। accept loop और request workers अब अलग executors में चलते हैं और `start()` हर बार नए executors बनाता है, इसलिए server restart भी सुरक्षित है।

### 1.3.4 नया JVM टेस्ट

`app/src/test/java/com/sb/backend/core/BackendShutdownSequenceTest.kt` — 3 टेस्ट:

- सटीक क्रम: `incoming → signals-platform → kotak-feed → backend-db → signals-db`; DB close के दौरान gate सच में maintenance मोड में होता है (`acquireOperation(0)` → `null`); दूसरी `execute()` उसी result को दोबारा steps चलाए बिना लौटाती है।
- एक producer के फेल होने पर भी बाकी producers और दोनों DB close होते हैं; error सूची में कारण दर्ज।
- gate timeout पर DB close **नहीं** होते (`drained=false`) — अधूरा shutdown झूठे "सफल" की रिपोर्ट नहीं देता।

## 1.4 DB backup/restore hardening — JVM safety layer, दोनों DB अलग रखकर

**नई फ़ाइलें:**
- `app/src/main/java/com/sb/backend/database/DatabaseBackupPolicy.kt` — `DatabaseSpec`, वर्ज़न कांस्टेंट, checkpoint/validation परिणाम, scope/naming नीति।
- `app/src/main/java/com/sb/backend/database/DatabaseFileBackup.kt` — gate-protected generic backup/restore engine।
- `app/src/main/java/com/sb/backend/database/AndroidDatabaseFileInspector.kt` — read-only SQLite inspector (`integrity_check`, `user_version`, `sqlite_master`)।
- `app/src/main/java/com/sb/signals/data/SignalDatabaseBackup.kt` — सिर्फ `sb_signals.db` के लिए owner-scoped wrapper।

**व्यवहार:**
- `DatabaseSpecs.BACKEND` → `sb_backend.db`, v11, prefix `sb_backend`, backup dir `db_backups`; `DatabaseSpecs.SIGNALS` → `sb_signals.db`, v4, prefix `sb_signals`, backup dir `db_backups/signals`। **कोई मर्ज या shared path नहीं**; वर्ज़न/टेबल कांस्टेंट owner helper से टेस्ट में मिलाए गए।
- backup: `DatabaseMaintenanceGate` लेता है; `PRAGMA wal_checkpoint(FULL)` के busy/log/checkpointed परिणाम जाँचता है; file copy के बाद integrity + user_version + required tables validate करता है; टाइमस्टैम्प colliding होने पर suffix जोड़ता है।
- restore: सिर्फ उसी owner DB पर चलता है; candidate validate; same-directory temp file पर stage; rollback snapshot; `-wal/-shm/-journal` sidecars हटाएँ; atomic replace (fallback copy); replace के बाद फिर validate; किसी भी विफलता पर rollback; सफलता पर rollback cleanup।
- `DatabaseBackup` पुराना constructor/API संभालते हुए अब backend policy पर delegate करता है। `SignalsPlatform.backup(context)` और `SignalsPlatform.restore(context, file)` जोड़े गए; restore तभी चलेगा जब signals running न हो, और सफल restore पर owner DB close होगा।

**JVM टेस्ट (10):**
- `DatabaseBackupPolicyTest` (4): spec/owner/scope अलगाव, वर्ज़न सिंक, backup filename collision, checkpoint/integrity/version/schema validator।
- `DatabaseFileBackupTest` (6): round-trip, backend/signals scope अलगाव, busy checkpoint block, gate held block, corrupt candidate reject, post-replace validation failure rollback।
- इन सभी में Shizuku/ADB/live DB नहीं — सिर्फ temp files + fake inspector।

## 1.5 Timeframe और chart↔DB cross-checks — JVM fixtures के साथ

**नई/बदली गई फ़ाइलें:**
- `app/src/main/java/com/sb/backend/market/analysis/TimeframeAggregator.kt` — शुद्ध deterministic aggregator: normalized event time से sort, `timestamp <= 0` skip, `open=first`, `high=max`, `low=min`, `close=last`, `volume=sum`, bucket start और target label लिखता है।
- `SignalScanCoordinator.aggregateCandles()` अब इसी aggregator पर delegate करता है; पुरानी duplicate logic हटा दी गई।
- `app/src/main/java/com/sb/backend/market/analysis/CandleRowMapper.kt` — DB cursor के 10 columns को pure `Candle` में बदलता है; `MarketRepository.readCandle()` अब इसी mapper पर delegate करता है।
- `docs/SIGNAL_ACCURACY_STANDARD.md` — cross-check status सच के अनुसार अपडेट।

**JVM टेस्ट (5):**
- `TimeframeAggregatorTest` (3): fixed IST timestamps पर 1m→5m→15m→1h चेन (expected OHLCV 100/161/99/160, volume 2370), direct 1h parity, `CandleSeriesTools.aggregate` + `CandleSeriesAudit` से exactMatch; seconds normalization, non-positive skip; unsorted input में timestamp-order close।
- `ChartOhlcvEquivalenceTest` (2): DB row → `CandleRowMapper` → `Candle` → `CandleSeriesTools.toMarketCandle` → `ChartCandle` → `ChartCandleSeries.canonicalize` → `CandleSeriesAudit` round-trip exactMatch; invalid rows drop और duplicate timestamp पर last-row keep।

**सीमा:** ये cross-check fixed deterministic fixtures पर हैं। बाहरी independently-sourced historical bars और असली instruments/sessions के live samples अभी पेंडिंग हैं; लाइव accuracy claim नहीं किया गया।

## 1.6 Compile + Unit Tests + APK Build — सफल

| चेक | कमांड | परिणाम |
|---|---|---|
| Kotlin compile | `JAVA_HOME=/opt/java/jdk-17.0.20.1+1 ./gradlew compileDebugKotlin --offline` | **BUILD SUCCESSFUL** |
| सभी JVM unit tests + APK | `JAVA_HOME=/opt/java/jdk-17.0.20.1+1 ./gradlew test assembleDebug --offline` | **BUILD SUCCESSFUL** — Debug **291 tests**, Release **291 tests**; दोनों में 0 failures, 0 errors, 0 skipped |
| APK आउटपुट | `app/build/outputs/apk/debug/app-debug.apk` | **15 MB**, timestamp 21 Nov 16:03 |
| Android lint | `JAVA_HOME=/opt/java/jdk-17.0.20.1+1 GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m' ./gradlew lintDebug --offline --no-daemon` | रिपोर्ट बनी; केवल **पहले से मौजूद** `ExpiredTargetSdkVersion` error (`targetSdkVersion 29`) — इस रन में platform/target SDK बदला नहीं गया |

कुल 291 = पिछले 268 + drawing persistence/config के 23 नए JVM टेस्ट (`ChartDrawingPolicyTest` 3, `ChartDrawingCodecTest` 3, `ChartDrawingRepositoryTest` 2, `ChartDrawingBridgeTest` 3, `IndicatorConfigPolicyTest` 4, `IndicatorConfigCodecTest` 3, `IndicatorConfigRepositoryTest` 1, `IndicatorConfiguredCacheTest` 3, `IndicatorConfigPresetTest` 1)। डोमेन-वाइज़ कवरेज अब DB backup/restore policy, owner scoping, rollback, timeframe rollup, chart/DB OHLCV equivalence, drawing serialization/bridge और configurable indicator/cache parity तक बढ़ गया है।

**नोट:** compile के दौरान कुछ पहले से मौजूद Kotlin warnings आए (deprecated `toUpperCase`, unused variables, opt-in annotation, label shadowing, `MarketRepository` में unreachable-code warnings) — ये **गैर-घातक** हैं, build सफल है।

## 1.7 चार्ट drawing persistence + configurable indicators

**नई pure-JVM लेयर:**
- `app/src/main/java/com/sb/ui/chart/persistence/`: `ChartDrawingModel.kt`, `ChartDrawingCodec.kt`, `ChartDrawingBridge.kt`, `ChartDrawingRepository.kt`, `ChartStateScope.kt`।
- `app/src/main/java/com/sb/ui/chart/config/`: `IndicatorConfig.kt`, `IndicatorConfigCodec.kt`, `IndicatorConfigPresets.kt`, `IndicatorConfigRepository.kt`।
- Android storage adapters: `SharedPreferencesChartDrawingStorage.kt`, `SharedPreferencesIndicatorConfigStorage.kt` — दोनों scope-key अलग करते हैं (`exchange|symbol|timeframe`) और `applicationContext` इस्तेमाल करते हैं।

**Drawing persistence:**
- Drawings अब index-based `ChartDrawing` से timestamp-anchored `ChartDrawingDocument` में बदले जाते हैं, जिससे timeframe/data refresh पर index बदलने पर भी annotations सही candle से जुड़े रहते हैं।
- `ChartDrawingPolicy` आकार/टूल-वाइज़ point count, id, timestamp, price, text length और duplicates validate करती है; `normalize()` deterministic ordering देती है।
- `ChartDrawingCodec` `org.json` से stable, escaped JSON बनाता है और invalid/wrong-version दस्तावेज़ reject करता है।
- `ChartDrawingBridge.export/import` unmappable timestamps वाली drawings drop करते हैं और index↔timestamp mapping सुरक्षित रखते हैं।
- `ProfessionalChartView` में नए non-breaking तरीके `exportDrawingDocument(...)` और `applyDrawingDocument(...)` जोड़े गए; पुराने private field नाम (`drawings`, `pendingPoints`) और सार्वजनिक signatures वैसे ही रखे गए, क्योंकि instrumented टेस्ट उन पर reflect करते हैं।

**Configurable indicators:**
- `IndicatorConfig` सभी periods, Bollinger/Supertrend multipliers, source (`CLOSE/OPEN/HIGH/LOW/TYPICAL_PRICE`) और enabled set रखता है; `IndicatorConfigPolicy.validate/normalize/label` नीति तय करते हैं।
- डिफ़ॉल्ट कॉन्फ़िगरेशन पुराने hardcode मानों के बराबर है: EMA 20, SMA 50, RSI 14, MACD 12/26/9, Bollinger 20×2, Supertrend 10×3, source CLOSE — इसलिए पुराना व्यवहार नहीं बदला।
- `IndicatorIncrementalCache.update(candles, config)` नया typed overload जोड़ा गया; पुराना `update(candles, indicators, timeframeKey)` उसी पर delegate करता है। config बदलने पर cache full recalculate करता है; reflective fields `indicatorKey`, `timeframeKey`, `result` सुरक्षित हैं।
- `ProfessionalChartView.indicatorConfig` नया property है, पर पुराना `indicators` setter भी काम करता है। `HeikinAshiChartScreen` में indicator menu से Default/Scalp/Swing/Typical presets चुनने, indicators toggle करने, timeframe बदलने, text annotation जोड़ने, clear करने और detach होने पर state save/load होता है।

**JVM टेस्ट (23):** serialization/escaping, validation, repository load/save/clear, bridge mapping, config policy/codec/repository, presets validity, custom period/source पर cache output और legacy-default parity — सब deterministic, बिना Android/instrumented execution के।

**सीमा:** लाइव UI पर दिखने वाला save/restore cycle अभी instrumented/manual verification के लिए पेंडिंग है; नए indicators (Ichimoku, ADX, Stochastic, OBV, Donchian), alerts और PNG export अभी पेंडिंग हैं।

## 1.8 Shizuku (rish) की ताज़ा जाँच — read-only

यह जाँच सिर्फ निरीक्षण थी; **Shizuku को ज़बरन चलाने की कोशिश नहीं की गई**।

| जाँच | परिणाम | निष्कर्ष |
|---|---|---|
| `rish` (882 B) और `rish_shizuku.dex` (59,672 B) | मौजूद हैं | फ़ाइलें हैं, पर चलती नहीं |
| `./rish 'echo RISH_OK'` | `/system/bin/sh: bad interpreter: No such file or directory`, **EXIT 126** | shebang interpreter मौजूद नहीं |
| `bash rish 'echo RISH_OK'` | `getprop: command not found` → `/system/bin/app_process: No such file or directory`, **EXIT 127** | स्क्रिप्ट अपने आवश्यक बिना चल ही नहीं सकती |
| `/system/bin/app_process`, `app_process64`, `/system/bin/sh`, `/system/bin/getprop` | सब `No such file or directory` | Shizuku का मुख्य प्रवेश बिंदु अनुपलब्ध |
| `/system` और `/system/bin` | directory ही मौजूद नहीं | अलग mount namespace / sandbox |
| `RISH_APPLICATION_ID` | `<unset>` | terminal app package सही सेट नहीं |
| Shizuku/Rish/app_process प्रोसेस | `ps -A` में कोई नहीं | सेवा सक्रिय नहीं |
| `/dev/*shizuku*` | मौजूद नहीं; `/dev/binderfs` permission denied | binder सुलभ नहीं |
| `adb devices` | `No such file or directory`, **EXIT 127**; emulator/system-image नहीं | ADB रास्ता भी बंद |

**निष्कर्ष:** Shizuku लाइव इस्तेमाल में **नहीं** लाया जा सकता; स्थिति पहले जैसी रुकी हुई है। सैंडबॉक्स की वजह से यह हमारी पहुँच से बाहर है — परमिशन देना या `RISH_APPLICATION_ID` सेट करना भी तब तक काम नहीं करेगा जब तक `/system/bin/app_process` और Shizuku सेवा मौजूद न हों।

---

# सेक्शन 2 — पेंडिंग वाला वर्क (Pending Work)

> जो काम अभी पूरे नहीं हुए या आगे के लिए रखे गए।

## 2.1 लैपटॉप / फ़ोन पर — लाइव UI / instrumented टेस्ट (यहाँ कोशिश नहीं की)

- `app/src/androidTest/java/com/sb/ui/chart/ProfessionalChartViewTest.kt` (722 लाइन, 11 टेस्ट) — चार्ट rendering, pan/pinch/zoom, pixel clipping, drawing remap। **लिखे गए पर कभी execute नहीं हुए।**
- `app/src/androidTest/java/com/sb/backend/database/MarketRepositoryQuoteOrderingTest.kt` (324 लाइन), `DatabaseMigrationsInstrumentedTest.kt`, `LegacyPaperMigrationInstrumentedTest.kt`, `PaperRepositoryInstrumentedTest.kt`, `SignalMigrationsInstrumentedTest.kt` — DB instrumented tests।
- चलाने के लिए host PC पर: JDK 17 + Android SDK platform-tools (adb) + emulator (API 29) या USB-debug device with RSA authorization; फिर `./gradlew connectedDebugAndroidTest`।
- APK पहले से तैयार है: `app/build/outputs/apk/debug/app-debug.apk` (15 MB) — लैपटॉप पर `adb install -r`, `adb shell am start -n com.sb/.MainActivity`, screenshot/`uiautomator` से UI प्रमाण।
- Shizuku मार्ग तब ही खुलेगा जब बाहरी डिवाइस पर Shizuku ऐप install/start हो, terminal app (`com.m4coding.ide`) को permission मिले, `RISH_APPLICATION_ID` सही set हो और `/system/bin/app_process` सुलभ हो।

## 2.2 DB backup/restore hardening — बचा हुआ हिस्सा

**JVM स्तर कंप्लीट** (सेक्शन 1.4 देखें): owner-scoped specs, checkpoint/validation, staged atomic restore, rollback, sidecar cleanup और 10 deterministic टेस्ट।

**अभी पेंडिंग:**
- Instrumented round-trip असली Android SQLite पर चलाना: valid backup→restore, corrupt/empty/incompatible reject, concurrent writer exclusion और process-kill के बाद rollback snapshot का व्यवहार।
- Gate coverage अधूरी है: API के कुछ सीधे `BackendManager.database()` queries और `signals.data.SignalRepository` के UI रीडर अभी `DatabaseMaintenanceGate` से नहीं गुज़रते; coordinated quiescence को पूरी तरह सच बनाने के लिए इन्हें भी operation lease देना है।
- Restore के बाद owner helper का reopen/refresh व्यवहार live app में verify करना (`DatabaseBackup` और `SignalsPlatform` सफल restore पर owner को बंद करते हैं)।

## 2.3 SIGNAL_ACCURACY_STANDARD.md — बचा हुआ हिस्सा

**JVM स्तर कंप्लीट** (सेक्शन 1.5 देखें): fixed fixtures पर 1m→5m→15m→1h chain, `CandleSeriesTools`/`CandleSeriesAudit` parity, seconds normalization, unsorted-order close, DB row→chart OHLCV round-trip; `docs/SIGNAL_ACCURACY_STANDARD.md` अपडेट किया गया।

**अभी पेंडिंग:**
- **Independently-sourced historical bars** के विरुद्ध aggregation मिलान (`docs/SIGNAL_ACCURACY_STANDARD.md:32`) — अभी synthetic deterministic fixtures हैं, बाहरी डेटा source नहीं।
- **Sample instruments/sessions पर live chart↔DB equivalence** (`:34`, `:79`) — JVM fixture test है, असली DB/UI samples नहीं।
- **Release gates:** 80–90% directional accuracy अभी सिर्फ research target है; walk-forward/out-of-sample metrics, coverage, costs, drawdown प्रकाशित नहीं हुए — live-trading readiness claim नहीं है।

## 2.4 Advanced चार्ट फीचर्स — बचा हुआ हिस्सा

**JVM स्तर कंप्लीट** (सेक्शन 1.7 देखें): drawing persistence timestamp-anchored JSON के साथ, scope-आधारित repository/storage, non-breaking view hooks; configurable indicators, presets, typed cache overload, legacy-default parity।

**अभी पेंडिंग:**
- लाइव UI/instrumented verification: timeframe बदलने, app restart और multi-scenario पर drawings/config का वास्तविक save/restore cycle।
- Room/DB persistence या cloud sync चाहिए तो अलग migration/स्टाइल-गाइड तय करनी होगी; अभी SharedPreferences with versioned JSON है।
- नए indicators — Ichimoku, ADX, Stochastic, OBV, Donchian।
- Price alerts और chart PNG export/share।

## 2.5 प्लेटफ़ॉर्म / अन्य (पेंडिंग)

- Migration verification (हर पुराने schema से upgrade, rollback, backup integrity) — दोनों DB के लिए अलग-अलग।
- Cloud identity/multi-user boundary, API network-exposure authorization, broker-neutral contract, scale validation।
- Repo hygiene: `backups/`, `gradle_info.txt` जैसे history artifacts का अलग archive नीति तय करना (इस रन में hprof/जंक हटाए गए; बाकी history files को छुआ नहीं गया)।

*यह रिपोर्ट पूरी तरह हिंदी भाषा में है और एक ही फ़ाइल में दो स्पष्ट सेक्शन रखती है: कंप्लीट वाला वर्क और पेंडिंग वाला वर्क।*
