# Phase 2 — Research Signal Hardening

This branch is an observation/paper-signal hardening stage. It does not authorize live orders.

## Revisions in this stage

- Active Intraday configuration restricted to 5m primary / 15m confirmation / 1h trend.
- Swing uses daily primary / weekly confirmation / monthly trend; Delivery uses daily / weekly / monthly. Swing reader now loads/aggregates a separate monthly trend series.
- Intraday variants at 1m, 3m, 10m, 15m and 30m remain disabled pending separate validation.
- Trend-timeframe confirmation checks that timeframe's own close and EMA fast/slow alignment.
- Candidate gates require primary EMA structure, higher timeframe EMA alignment, trend timeframe EMA alignment, directional ADX, either RSI or MACD confirmation, and either volume expansion or breakout.
- Bollinger scoring no longer grants directional points to price on the wrong side of its middle band.
- Entry/stop/target levels are validated for finite, positive, correctly ordered values.
- Strategy config validates finite risk and threshold values.
- Higher timeframe minimum candle counts are increased to 60 to support EMA-50 confirmation.
- Signal analysis rejects candidates whose primary candle exceeds configured maximum age.
- Candle quality reports suspicious data gaps and lowers quality score.
- Indicator performance includes a sample adequacy flag; 30 confirmed completed samples is a conservative reporting threshold, not proof of predictive causality.

## Validation status

Strategy configurations are versioned 1.2.0 after rule changes; older persisted signals retain their stored strategy configuration and evaluation history.

Source changes are committed on this feature branch. Build and JVM unit tests still need CI confirmation. No live trading, broker order execution, Paper Trading balance/order modifications, database reset, or data deletion is part of this stage.