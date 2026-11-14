# SB Signal Accuracy Standard

## Objective

Target: 80–90% verified directional accuracy for eligible SB calls, measured out-of-sample after costs. This is a research target, not a promised result or a claim about current performance. No live-trading readiness claim is permitted until the acceptance gates below pass.

## What counts as a call

Every evaluated call must be immutable and include:
- instrument, exchange, timestamp, timeframe and data-source identifier;
- signal side (BUY/SELL; HOLD is tracked separately), entry/reference price;
- stop-loss, target(s), expiry/horizon and strategy/version identifier;
- feature snapshot: OHLCV window, indicators, trend/regime and detected patterns;
- confidence score and reason codes.

Never count multiple updates of the same call as separate wins. Define one primary horizon per strategy before testing.

## Data correctness gates (must pass first)

1. Validate OHLC invariants: high >= max(open, close), low <= min(open, close), prices finite and positive, volume finite and non-negative.
2. Use exchange event time for candle buckets; retain receive time separately. Store timestamps in UTC and display in exchange-local time (IST for NSE/BSE).
3. Define candle timestamp consistently as bucket start. Enforce unique key (exchange, instrument, timeframe, bucket timestamp).
4. Aggregate base candles/ticks deterministically: open=first, high=max, low=min, close=last, volume=sum of incremental traded volume (or use documented cumulative-volume delta logic).
5. Reject out-of-order/duplicate ticks safely; handle late ticks by deterministic candle correction and recomputation.
6. Distinguish forming candles from finalized candles. Do not use an unfinished candle for historical pattern labels or backtest decisions unless the strategy explicitly models intrabar signals.
7. Verify gaps, duplicate rows, timezone/DST handling, session boundaries, corporate actions and instrument-token changes.
8. Persist source, quality flags and data version. Never fabricate missing candles or volume.

## Chart and timeframe gates

- Chart candles must be derived from the same canonical candle series used by analysis.
- Verify 1m -> 5m -> 15m -> 1h aggregation against independently sourced historical bars.
- No candle may appear before its timestamp; ordering must be strictly ascending and unique.
- Verify chart OHLCV against database rows for sampled instruments and sessions.
- Indicators must specify warm-up length, input series and whether current candle is closed.

## Pattern and analysis gates

- Each pattern has explicit mathematical rules, configurable tolerances and unit tests for positive, negative, boundary and noisy examples.
- Pattern detection runs only on completed candles unless specifically designed for live/intrabar detection.
- Avoid treating a named pattern as a trading call by itself. Combine trend, market regime, volume, volatility, support/resistance and risk/reward.
- Avoid look-ahead bias, repainting and future-data leakage.

## Accuracy measurement

Report at minimum:
- directional accuracy = correct eligible BUY/SELL calls / all eligible BUY/SELL calls;
- precision and recall separately for BUY and SELL;
- coverage = eligible calls / all decision opportunities;
- profit factor, expectancy, average win/loss, maximum drawdown and net return after brokerage, taxes, slippage and spread;
- calibration by confidence bucket, instrument, timeframe, strategy and market regime.

A HOLD is not a winning call. Report abstention/coverage separately. Do not optimize accuracy by issuing almost no calls without showing coverage.

## Validation protocol

1. Freeze a versioned strategy and data snapshot.
2. Split data chronologically into train, validation and untouched test periods; never random-shuffle time series.
3. Use walk-forward validation across multiple market regimes.
4. Include realistic fees, taxes, slippage, spread, latency and rejected orders.
5. Prevent leakage: indicators and labels at decision time may use only information available then.
6. Compare with simple baselines and report confidence intervals/sample counts.
7. Paper trade live for a predeclared period; reconcile every signal with subsequent market data.
8. Re-run tests after any data, indicator, pattern or strategy change.

## Release gates

- Data integrity tests pass with zero unexplained duplicate timestamps or invalid OHLC rows.
- Timeframe aggregation and chart/database reconciliation pass.
- Pattern unit tests pass and no repaint/look-ahead is found.
- Out-of-sample and walk-forward metrics are published with sample sizes and costs.
- The 80–90% target is considered achieved only if the pre-registered directional metric reaches the target across a sufficiently large, representative sample while coverage, drawdown and net expectancy remain acceptable. Accuracy alone is insufficient.
- Until these gates pass, calls remain research/paper-trading only. No promise of returns or accuracy.

## Work order

1. Canonical candle data pipeline and persistence.
2. Historical/live reconciliation and timeframe aggregation.
3. Chart data-source consistency.
4. Pattern definitions and test suite.
5. Signal event log and immutable evaluation labels.
6. Backtest/walk-forward evaluator with costs.
7. Paper-trading monitoring and calibration.
8. Controlled live rollout only after independent review.
