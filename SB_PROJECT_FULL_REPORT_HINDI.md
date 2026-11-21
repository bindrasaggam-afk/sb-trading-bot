# SB (SBA) प्रोजेक्ट — अपडेटेड A-to-Z रिपोर्ट

**अपडेट की तारीख:** 21 नवंबर 2026 (शनिवार)
**प्रोजेक्ट:** SB Trading Bot — Android / Kotlin ऐप + लोकल मार्केट-डेटा और ट्रेडिंग बैकएंड
**प्रोजेक्ट पाथ:** `/storage/internal_new/project/sbApp`
**ब्रांच:** `feature/platform-hardening`
**इस रन का दायरा:** सिर्फ वे काम जो इस मोबाइल एनवायरनमेंट में चल सकते हैं — compile, JVM unit tests, `assembleDebug`, Git cleanup/commit और दस्तावेज़। लाइव ADB / emulator / Shizuku / `rish` वाले UI/instrumented टेस्ट **नहीं चलाए गए** और लैपटॉप के लिए पेंडिंग हैं।

**मुख्य परिणाम:** `./gradlew test` सफल — Debug 253/253 और Release 253/253 JVM unit tests पास (0 fail, 0 skip); `assembleDebug` सफल — APK 14 MB; 5 लॉजिकल commits किए गए; Git working tree साफ़ (सिर्फ ये रिपोर्ट अंतिम commit के लिए बाकी)।

**स्थिति सारांश:**

| # | काम | स्थिति |
|---|---|---|
| 1 | Deterministic candle engine + session-aware pipeline | **कंप्लीट** |
| 2 | Repository cleanup (`.hprof`, जंक फ़ाइलें) + 5 logical commits | **कंप्लीट** |
| 3 | दोनों DB अलग-अलग, प्रॉपर init + coordinated facade/shutdown | **कंप्लीट** |
| 4 | `./gradlew test` (253/253 × 2) + `assembleDebug` (14 MB APK) | **कंप्लीट** |
| 5 | Shizuku (rish) ताज़ा जाँच — बंद/अनुपलब्ध, इस्तेमाल संभव नहीं | **कंप्लीट (निरीक्षण)** |
| 6 | लैपटॉप/फ़ोन पर लाइव UI + instrumented टेस्ट | **पेंडिंग** |
| 7 | DB backup/restore hardening + gate coverage | **पेंडिंग** |
| 8 | `1m→5m→15m→1h` और chart↔DB cross-check | **पेंडिंग** |
| 9 | Advanced चार्ट फीचर्स + प्लेटफ़ॉर्म स्केल/सिक्योरिटी | **पेंडिंग** |

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
| 5 | अंतिम | `docs: refresh Hindi report for dual-database design and verification` — ये रिपोर्ट फ़ाइल |

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

## 1.4 Compile + Unit Tests + APK Build — सफल

| चेक | कमांड | परिणाम |
|---|---|---|
| Kotlin compile | `JAVA_HOME=/opt/java/jdk-17.0.20.1+1 ./gradlew compileDebugKotlin --offline` | **BUILD SUCCESSFUL** |
| सभी JVM unit tests | `./gradlew test --offline` | **BUILD SUCCESSFUL** — Debug **253 tests**, Release **253 tests**; दोनों में 0 failures, 0 errors, 0 skipped |
| APK build | `./gradlew assembleDebug --offline` | **BUILD SUCCESSFUL** |
| APK आउटपुट | `app/build/outputs/apk/debug/app-debug.apk` | **14 MB**, timestamp 21 Nov 09:13 |

कुल 253 = पहले के 250 + नए `BackendShutdownSequenceTest` के 3 टेस्ट। डोमेन-वाइज़ कवरेज पहले जैसा ही है (डेटा/candle, चार्ट, सिग्नल, पेपर ट्रेडिंग, API/सिक्योरिटी, overview, research, DB gate, Kotak parser)।

**नोट:** compile के दौरान कुछ पहले से मौजूद Kotlin warnings आए (deprecated `toUpperCase`, unused variables, opt-in annotation, label shadowing) — ये **गैर-घातक** हैं, build सफल है।

## 1.5 Shizuku (rish) की ताज़ा जाँच — read-only

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
- APK पहले से तैयार है: `app/build/outputs/apk/debug/app-debug.apk` (14 MB) — लैपटॉप पर `adb install -r`, `adb shell am start -n com.sb/.MainActivity`, screenshot/`uiautomator` से UI प्रमाण।
- Shizuku मार्ग तब ही खुलेगा जब बाहरी डिवाइस पर Shizuku ऐप install/start हो, terminal app (`com.m4coding.ide`) को permission मिले, `RISH_APPLICATION_ID` सही set हो और `/system/bin/app_process` सुलभ हो।

## 2.2 DB backup/restore hardening (अभी पेंडिंग)

- `DatabaseBackup` सिर्फ `sb_backend.db` को टारगेट करता है; `sb_signals.db` का backup/restore अलग से तय करना है — **दोनों को मर्ज किए बिना** अलग-अलग candidate file और restore path रखने होंगे।
- WAL checkpoint का पूरा result validate; app-controlled candidate file; same-filesystem temp + atomic replace; rollback snapshot; SQLite integrity/schema check।
- Isolated instrumented tests (बाहर चलाने होंगे): valid round-trip, corrupt/empty/incompatible reject, failure rollback, concurrent writer exclusion।
- Gate coverage अधूरी है: API के कुछ सीधे `BackendManager.database()` queries और `signals.data.SignalRepository` के UI रीडर अभी `DatabaseMaintenanceGate` से नहीं गुज़रते; coordinated quiescence को पूरी तरह सच बनाने के लिए इन्हें भी operation lease देना है।

## 2.3 SIGNAL_ACCURACY_STANDARD.md के cross-check (पेंडिंग)

- **1m → 5m → 15m → 1h aggregation:** independently-sourced historical bars के विरुद्ध deterministic test बनाना बाकी (`docs/SIGNAL_ACCURACY_STANDARD.md:32`)।
- **Chart ↔ DB OHLCV equivalence:** sample instruments/sessions के लिए chart boundary और DB rows का मिलान बाकी (`:34`)। Chart-boundary canonicalization (`ChartCandleSeries.canonicalize`) और उसके 3 टेस्ट हो गए हैं; cross-source equivalence अभी pending है (`:79`)।
- **Release gates:** 80–90% directional accuracy अभी सिर्फ research target है; walk-forward/out-of-sample metrics, coverage, costs, drawdown प्रकाशित नहीं हुए — live-trading readiness claim नहीं है।

## 2.4 Advanced चार्ट फीचर्स (पेंडिंग)

- ड्रॉइंग्स का Room/DB persistence (अभी सिर्फ memory)।
- Configurable indicator periods — `EMA_20`, `SMA_50`, `RSI_14` hardcode (`ChartModels.kt`)।
- नए indicators — Ichimoku, ADX, Stochastic, OBV, Donchian।
- Price alerts और chart PNG export/share।

## 2.5 प्लेटफ़ॉर्म / अन्य (पेंडिंग)

- Migration verification (हर पुराने schema से upgrade, rollback, backup integrity) — दोनों DB के लिए अलग-अलग।
- Cloud identity/multi-user boundary, API network-exposure authorization, broker-neutral contract, scale validation।
- Repo hygiene: `backups/`, `gradle_info.txt` जैसे history artifacts का अलग archive नीति तय करना (इस रन में hprof/जंक हटाए गए; बाकी history files को छुआ नहीं गया)।

*यह रिपोर्ट पूरी तरह हिंदी भाषा में है और एक ही फ़ाइल में दो स्पष्ट सेक्शन रखती है: कंप्लीट वाला वर्क और पेंडिंग वाला वर्क।*
