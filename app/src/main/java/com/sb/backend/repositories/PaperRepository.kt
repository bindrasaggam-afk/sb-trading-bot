package com.sb.backend.repositories

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.sb.backend.core.AppConfig
import com.sb.backend.database.DatabaseMaintenanceGate
import com.sb.backend.database.SBDatabaseHelper
import com.sb.backend.models.OrderSide
import com.sb.backend.models.OrderStatus
import com.sb.backend.models.PaperExecution
import com.sb.backend.models.PaperExecutionKind
import com.sb.backend.models.PaperOrder
import com.sb.backend.models.PaperTradeEvent
import com.sb.backend.models.Position
import com.sb.backend.trading.PaperCapitalPolicy
import com.sb.backend.trading.PaperCapitalState
import com.sb.backend.trading.PaperSignalCandidate
import com.sb.backend.trading.PaperSignalPolicy
import com.sb.backend.trading.PaperSizingRules
import java.util.Locale

data class PaperAccount(
    val startingBalance: Double,
    val balance: Double,
    val reservedMargin: Double,
    val realizedPnl: Double,
    val updatedAt: Long
) {
    val availableBalance: Double
        get() = balance - reservedMargin
}

data class PaperSnapshotData(
    val account: PaperAccount,
    val positions: List<Position>,
    val realizedPnl: Double,
    val unrealizedPnl: Double
)

data class SignalOrderSaveResult(
    val order: PaperOrder,
    val inserted: Boolean,
    val reservedCapital: Double
)

class PaperRepository(
    private val databaseHelper: SBDatabaseHelper,
    private val maintenanceGate: DatabaseMaintenanceGate =
        DatabaseMaintenanceGate.shared
) {

    fun ensureAccount(startingBalance: Double): PaperAccount {
        return maintenanceGate.withOperation {
            require(startingBalance.isFinite() && startingBalance > 0.0) {
                "Starting balance must be a valid positive amount."
            }

            val existing = getAccount()
            if (existing != null) return existing

            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("id", 1)
                put("starting_balance", startingBalance)
                put("balance", startingBalance)
                put("reserved_margin", 0.0)
                put("realized_pnl", 0.0)
                put("updated_at", now)
            }

            databaseHelper.writableDatabase.insertWithOnConflict(
                SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )

            PaperAccount(
                startingBalance = startingBalance,
                balance = startingBalance,
                reservedMargin = 0.0,
                realizedPnl = 0.0,
                updatedAt = now
            )
        }
    }

    fun hasAccount(): Boolean = maintenanceGate.withOperation {
        getAccount() != null
    }

    fun getAccount(): PaperAccount? {
        return maintenanceGate.withOperation {
            databaseHelper.readableDatabase.query(
                SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                accountProjection,
                "id = ?",
                arrayOf("1"),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) readAccount(cursor) else null
            }
        }
    }

    fun setStartingBalance(startingBalance: Double): PaperAccount {
        return maintenanceGate.withOperation {
            require(startingBalance.isFinite() && startingBalance > 0.0) {
                "Starting balance must be a valid positive amount."
            }

            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                check(countOrders(db) == 0) {
                    "Starting balance cannot be changed after paper orders exist."
                }
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("id", 1)
                    put("starting_balance", startingBalance)
                    put("balance", startingBalance)
                    put("reserved_margin", 0.0)
                    put("realized_pnl", 0.0)
                    put("updated_at", now)
                }
                db.insertWithOnConflict(
                    SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                db.setTransactionSuccessful()
                PaperAccount(
                    startingBalance = startingBalance,
                    balance = startingBalance,
                    reservedMargin = 0.0,
                    realizedPnl = 0.0,
                    updatedAt = now
                )
            } finally {
                db.endTransaction()
            }
        }
    }

    fun saveSignalOrder(
        candidate: PaperSignalCandidate,
        quantity: Int
    ): PaperOrder = saveSignalOrderDetailed(candidate, quantity).order

    fun saveSignalOrderDetailed(
        candidate: PaperSignalCandidate,
        quantity: Int
    ): SignalOrderSaveResult {
        return maintenanceGate.withOperation {
            require(candidate.signalId > 0L) {
                "Signal ID must be positive."
            }
            require(quantity > 0) {
                "Quantity must be greater than zero."
            }
            val normalizedSide = normalizeSide(candidate.side)
            val normalizedSymbol = candidate.symbol
                .trim().uppercase(Locale.ROOT)
            val normalizedExchange = candidate.exchange
                .trim().uppercase(Locale.ROOT)
            require(normalizedSymbol.isNotBlank()) {
                "Symbol cannot be empty."
            }
            require(normalizedExchange.isNotBlank()) {
                "Exchange cannot be empty."
            }
            require(candidate.referencePrice.isFinite() &&
                candidate.referencePrice > 0.0) {
                "Reference price must be valid and positive."
            }
            validateSignalLevels(candidate)

            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                val existing = queryOrderBySignalId(
                    db,
                    candidate.signalId
                )
                if (existing != null) {
                    db.setTransactionSuccessful()
                    return@withOperation SignalOrderSaveResult(
                        order = existing,
                        inserted = false,
                        reservedCapital = existing.reservedMargin
                    )
                }

                val account = queryAccount(db)
                    ?: throw IllegalStateException(
                        "Set starting balance first."
                    )
                val expectedEntry =
                    PaperSignalPolicy.expectedEntryPrice(
                        candidate = candidate,
                        applySlippage = true
                    )
                check(expectedEntry.isFinite() && expectedEntry > 0.0) {
                    "Expected entry price is invalid."
                }
                val rules = sizingRules(candidate.costBpsPerSide)
                val allowedQuantity = minOf(
                    quantity,
                    PaperSignalPolicy.initialQuantity(
                        candidate = candidate,
                        availableBalance = account.availableBalance,
                        rules = rules,
                        expectedEntryPrice = expectedEntry
                    )
                )
                check(allowedQuantity > 0) {
                    "Insufficient available capital for this signal."
                }
                val requirement = PaperSignalPolicy.capitalRequirement(
                    side = normalizedSide,
                    price = expectedEntry,
                    quantity = allowedQuantity,
                    costBpsPerSide = candidate.costBpsPerSide,
                    shortMarginRate = AppConfig.get().shortMarginRate
                )
                val capital = PaperCapitalPolicy.reserve(
                    state = capitalState(account),
                    amount = requirement.totalReservation
                )

                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("symbol", normalizedSymbol)
                    put("exchange", normalizedExchange)
                    put("side", normalizedSide)
                    put("quantity", allowedQuantity)
                    put("entry_price", expectedEntry)
                    put("reserved_margin", requirement.totalReservation)
                    put("status", OrderStatus.PENDING.name)
                    put("note", "Signal #${candidate.signalId} " +
                        "${candidate.strategyId} v" +
                        candidate.strategyVersion)
                    put("stop_loss", candidate.stopLoss)
                    put("target", candidate.target3)
                    put("entry_source", "SIGNAL")
                    putNull("entry_timestamp")
                    put("signal_id", candidate.signalId)
                    put("signal_category", candidate.category)
                    put("signal_timeframe", candidate.timeframe)
                    put("strategy_id", candidate.strategyId)
                    put("strategy_version", candidate.strategyVersion)
                    put(
                        "signal_generated_at",
                        candidate.generatedAt
                    )
                    put("signal_expires_at", candidate.expiresAt)
                    put("entry_low", candidate.entryLow)
                    put("entry_high", candidate.entryHigh)
                    put("cost_bps_per_side", candidate.costBpsPerSide)
                    put(
                        "slippage_bps_per_side",
                        candidate.slippageBpsPerSide
                    )
                    put("target1", candidate.target1)
                    put("target2", candidate.target2)
                    put("target3", candidate.target3)
                    put("remaining_quantity", allowedQuantity)
                    put("last_price", expectedEntry)
                    put("entry_charges", 0.0)
                    put("charges", 0.0)
                    put("exit_reason", "")
                    put("created_at", now)
                    put("updated_at", now)
                }

                val orderId = db.insertOrThrow(
                    SBDatabaseHelper.TABLE_PAPER_ORDERS,
                    null,
                    values
                )
                updateAccountCapital(
                    db = db,
                    state = capital,
                    updatedAt = now,
                    message = "Could not reserve capital."
                )
                insertEvent(
                    db = db,
                    orderId = orderId,
                    signalId = candidate.signalId,
                    type = "SIGNAL_FORWARDED",
                    message = "Genuine signal forwarded with " +
                        "${formatAmount(requirement.totalReservation)} " +
                        "capital reserved",
                    now = now
                )
                if (allowedQuantity < quantity) {
                    insertEvent(
                        db = db,
                        orderId = orderId,
                        signalId = candidate.signalId,
                        type = "SIGNAL_SIZED",
                        message = "Quantity reduced from $quantity to " +
                            "$allowedQuantity by paper risk limits",
                        now = now
                    )
                }
                db.setTransactionSuccessful()
                val saved = queryOrder(db, orderId)
                    ?: throw IllegalStateException(
                        "Could not read forwarded paper order."
                    )
                SignalOrderSaveResult(
                    order = saved,
                    inserted = true,
                    reservedCapital = requirement.totalReservation
                )
            } finally {
                db.endTransaction()
            }
        }
    }

    fun fillSignalOrder(
        orderId: Long,
        executionPrice: Double,
        executedAt: Long
    ): PaperOrder? {
        return maintenanceGate.withOperation {
            require(orderId > 0L) { "Invalid order ID." }
            require(executionPrice.isFinite() && executionPrice > 0.0) {
                "Entry price must be valid and positive."
            }
            require(executedAt > 0L) {
                "Execution timestamp must be positive."
            }

            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                val order = queryOrder(db, orderId)
                if (order == null ||
                    !order.isPending ||
                    order.signalId == null
                ) {
                    db.setTransactionSuccessful()
                    return@withOperation order
                }

                val account = queryAccount(db)
                    ?: throw IllegalStateException(
                        "Paper account is missing."
                    )
                val candidate = candidateForOrder(order, executionPrice)
                val availableWithReservation =
                    account.availableBalance + order.reservedMargin
                val rules = sizingRules(order.costBpsPerSide)
                val allowedQuantity = PaperSignalPolicy.initialQuantity(
                    candidate = candidate,
                    availableBalance = availableWithReservation,
                    rules = rules,
                    expectedEntryPrice = executionPrice
                )
                val quantity = minOf(order.quantity, allowedQuantity)
                check(quantity > 0) {
                    "Actual entry price exceeds available paper capital."
                }

                val requirement = PaperSignalPolicy.capitalRequirement(
                    side = order.side,
                    price = executionPrice,
                    quantity = quantity,
                    costBpsPerSide = order.costBpsPerSide,
                    shortMarginRate = AppConfig.get().shortMarginRate
                )
                val capital = PaperCapitalPolicy.applyEntry(
                    state = capitalState(account),
                    previousReservation = order.reservedMargin,
                    requirement = requirement
                )

                val maxOpenPositions = AppConfig.get().maxOpenPositions
                if (maxOpenPositions > 0) {
                    check(countOpenPositions(db) < maxOpenPositions) {
                        "Maximum open positions reached at entry."
                    }
                }

                val orderValues = ContentValues().apply {
                    put("quantity", quantity)
                    put("entry_price", executionPrice)
                    put("entry_timestamp", executedAt)
                    put("reserved_margin", requirement.reservedMargin)
                    put("status", OrderStatus.OPEN.name)
                    put("remaining_quantity", quantity)
                    put("last_price", executionPrice)
                    put("entry_charges", requirement.entryCharge)
                    put("charges", requirement.entryCharge)
                    put("updated_at", executedAt)
                }
                check(
                    db.update(
                        SBDatabaseHelper.TABLE_PAPER_ORDERS,
                        orderValues,
                        "id = ? AND status = ?",
                        arrayOf(
                            orderId.toString(),
                            OrderStatus.PENDING.name
                        )
                    ) == 1
                ) { "Could not open paper signal order." }

                val positionValues = ContentValues().apply {
                    put("order_id", orderId)
                    put("symbol", order.symbol)
                    put("exchange", order.exchange)
                    put("side", order.side)
                    put("quantity", quantity)
                    put("entry_price", executionPrice)
                    put("last_price", executionPrice)
                    put("reserved_margin", requirement.reservedMargin)
                    put("status", OrderStatus.OPEN.name)
                    put("opened_at", executedAt)
                    put("updated_at", executedAt)
                }
                db.insertOrThrow(
                    SBDatabaseHelper.TABLE_POSITIONS,
                    null,
                    positionValues
                )

                updateAccountCapital(
                    db = db,
                    state = capital,
                    updatedAt = executedAt,
                    message = "Could not update paper account at entry."
                )

                insertExecution(
                    db = db,
                    order = order,
                    kind = PaperExecutionKind.ENTRY,
                    quantity = quantity,
                    price = executionPrice,
                    grossPnl = 0.0,
                    charges = requirement.entryCharge,
                    netPnl = PaperCapitalPolicy.entryExecutionNetPnl(),
                    executedAt = executedAt,
                    eventKey = "$orderId:ENTRY",
                    note = "Signal entry filled"
                )
                insertEvent(
                    db = db,
                    orderId = orderId,
                    signalId = order.signalId,
                    type = "ENTRY_FILLED",
                    message = "Entry filled at " +
                        "${formatAmount(executionPrice)}; charges " +
                        formatAmount(requirement.entryCharge),
                    now = executedAt
                )
                if (quantity < order.quantity) {
                    insertEvent(
                        db = db,
                        orderId = orderId,
                        signalId = order.signalId,
                        type = "ENTRY_REVALIDATED",
                        message = "Quantity reduced from ${order.quantity} " +
                            "to $quantity at the actual entry price",
                        now = executedAt
                    )
                }

                db.setTransactionSuccessful()
                queryOrder(db, orderId)
            } finally {
                db.endTransaction()
            }
        }
    }

    fun saveAndFillSignalOrder(
        candidate: PaperSignalCandidate,
        quantity: Int,
        executionPrice: Double,
        executedAt: Long
    ): SignalOrderSaveResult {
        val saved = saveSignalOrderDetailed(candidate, quantity)
        if (!saved.inserted) return saved
        try {
            val filled = fillSignalOrder(
                orderId = saved.order.id,
                executionPrice = executionPrice,
                executedAt = executedAt
            ) ?: throw IllegalStateException(
                "Triggered paper order could not be filled."
            )
            return saved.copy(order = filled)
        } catch (error: Exception) {
            try {
                cancelSignalOrder(
                    orderId = saved.order.id,
                    reason = "Triggered entry failed: " +
                        (error.message ?: "unknown error"),
                    createdAt = executedAt
                )
            } catch (cancelError: Exception) {
                error.addSuppressed(cancelError)
            }
            throw error
        }
    }

    fun exitSignalOrder(
        orderId: Long,
        kind: PaperExecutionKind,
        quantity: Int,
        executionPrice: Double,
        executedAt: Long,
        note: String = ""
    ): PaperOrder? {
        return maintenanceGate.withOperation {
            require(orderId > 0L) { "Invalid order ID." }
            require(quantity > 0) {
                "Exit quantity must be greater than zero."
            }
            require(executionPrice.isFinite() && executionPrice > 0.0) {
                "Exit price must be valid and positive."
            }
            require(executedAt > 0L) {
                "Execution timestamp must be positive."
            }

            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                val order = queryOrder(db, orderId)
                if (order == null || !order.isOpen) {
                    db.setTransactionSuccessful()
                    return@withOperation order
                }
                val eventKey = "$orderId:${kind.name}"
                if (executionExists(db, eventKey)) {
                    db.setTransactionSuccessful()
                    return@withOperation order
                }

                val exitQuantity = quantity.coerceAtMost(
                    order.remainingQuantity
                )
                if (exitQuantity <= 0) {
                    db.setTransactionSuccessful()
                    return@withOperation order
                }
                val position = queryPositionByOrderId(db, orderId)
                    ?: throw IllegalStateException(
                        "Open paper position is missing."
                    )

                val grossPnl = PaperSignalPolicy.grossPnl(
                    side = order.side,
                    entryPrice = order.entryPrice,
                    exitPrice = executionPrice,
                    quantity = exitQuantity
                )
                val entryCharge = PaperSignalPolicy.allocatedEntryCharge(
                    totalEntryCharge = order.entryCharges,
                    originalQuantity = order.quantity,
                    exitQuantity = exitQuantity
                )
                val exitCharge = PaperSignalPolicy.exitCharge(
                    price = executionPrice,
                    quantity = exitQuantity,
                    costBps = order.costBpsPerSide
                )
                val netPnl = grossPnl - entryCharge - exitCharge
                val releaseReserved = if (position.quantity > 0) {
                    position.reservedMargin * exitQuantity /
                        position.quantity
                } else {
                    0.0
                }
                val remainingQuantity =
                    order.remainingQuantity - exitQuantity
                val remainingReserved =
                    (order.reservedMargin - releaseReserved)
                        .coerceAtLeast(0.0)

                val account = queryAccount(db)
                    ?: throw IllegalStateException(
                        "Paper account is missing."
                    )

                val positionValues = ContentValues().apply {
                    put("quantity", remainingQuantity)
                    put("last_price", executionPrice)
                    put("reserved_margin", remainingReserved)
                    put(
                        "status",
                        if (remainingQuantity <= 0) {
                            OrderStatus.CLOSED.name
                        } else {
                            OrderStatus.OPEN.name
                        }
                    )
                    put("updated_at", executedAt)
                }
                check(
                    db.update(
                        SBDatabaseHelper.TABLE_POSITIONS,
                        positionValues,
                        "id = ? AND status = ?",
                        arrayOf(
                            position.id.toString(),
                            OrderStatus.OPEN.name
                        )
                    ) == 1
                ) { "Could not update paper position." }

                val nowTargetAt = if (kind == PaperExecutionKind.TARGET_1 ||
                    kind == PaperExecutionKind.TARGET_2 ||
                    kind == PaperExecutionKind.TARGET_3
                ) {
                    executedAt
                } else {
                    null
                }
                val orderValues = ContentValues().apply {
                    put("remaining_quantity", remainingQuantity)
                    put(
                        "status",
                        if (remainingQuantity <= 0) {
                            OrderStatus.CLOSED.name
                        } else {
                            OrderStatus.OPEN.name
                        }
                    )
                    put("exit_price", executionPrice)
                    put(
                        "realized_pnl",
                        (order.realizedPnl ?: 0.0) + netPnl
                    )
                    put("reserved_margin", remainingReserved)
                    put("last_price", executionPrice)
                    put("charges", order.charges + exitCharge)
                    put(
                        "exit_reason",
                        if (remainingQuantity <= 0) {
                            if (note.isBlank()) kind.name else note
                        } else {
                            order.exitReason
                        }
                    )
                    when (kind) {
                        PaperExecutionKind.TARGET_1 ->
                            put("target1_at", executedAt)
                        PaperExecutionKind.TARGET_2 ->
                            put("target2_at", executedAt)
                        PaperExecutionKind.TARGET_3 ->
                            put("target3_at", executedAt)
                        PaperExecutionKind.STOP_LOSS ->
                            put("stopped_at", executedAt)
                        else -> Unit
                    }
                    put("updated_at", executedAt)
                }
                check(
                    db.update(
                        SBDatabaseHelper.TABLE_PAPER_ORDERS,
                        orderValues,
                        "id = ? AND status = ?",
                        arrayOf(
                            orderId.toString(),
                            OrderStatus.OPEN.name
                        )
                    ) == 1
                ) { "Could not update paper order." }

                val capital = PaperCapitalPolicy.applyExit(
                    state = capitalState(account),
                    side = order.side,
                    exitPrice = executionPrice,
                    exitQuantity = exitQuantity,
                    grossPnl = grossPnl,
                    exitCharge = exitCharge,
                    releaseReserved = releaseReserved
                )
                updateAccountCapital(
                    db = db,
                    state = capital,
                    updatedAt = executedAt,
                    message = "Could not update paper account at exit."
                )

                insertExecution(
                    db = db,
                    order = order,
                    kind = kind,
                    quantity = exitQuantity,
                    price = executionPrice,
                    grossPnl = grossPnl,
                    charges = entryCharge + exitCharge,
                    netPnl = PaperCapitalPolicy.exitExecutionNetPnl(
                        grossPnl = grossPnl,
                        allocatedEntryCharge = entryCharge,
                        exitCharge = exitCharge
                    ),
                    executedAt = executedAt,
                    eventKey = eventKey,
                    note = note.ifBlank { kind.name }
                )
                insertEvent(
                    db = db,
                    orderId = orderId,
                    signalId = order.signalId,
                    type = kind.name,
                    message = note.ifBlank {
                        "${kind.name} at ${formatAmount(executionPrice)}"
                    },
                    now = executedAt
                )
                if (nowTargetAt != null) {
                    insertEvent(
                        db = db,
                        orderId = orderId,
                        signalId = order.signalId,
                        type = "PARTIAL_EXIT",
                        message = "Remaining quantity $remainingQuantity",
                        now = executedAt
                    )
                }

                db.setTransactionSuccessful()
                queryOrder(db, orderId)
            } finally {
                db.endTransaction()
            }
        }
    }

    fun cancelSignalOrder(
        orderId: Long,
        reason: String,
        createdAt: Long = System.currentTimeMillis()
    ): PaperOrder? {
        return maintenanceGate.withOperation {
            require(orderId > 0L) { "Invalid order ID." }
            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                val order = queryOrder(db, orderId)
                if (order == null ||
                    !order.isPending ||
                    order.signalId == null
                ) {
                    db.setTransactionSuccessful()
                    return@withOperation order
                }
                val account = queryAccount(db)
                    ?: throw IllegalStateException(
                        "Paper account is missing."
                    )
                val released = order.reservedMargin
                    .coerceIn(0.0, account.reservedMargin)
                val capital = PaperCapitalPolicy.release(
                    state = capitalState(account),
                    amount = released
                )
                val values = ContentValues().apply {
                    put("status", OrderStatus.CANCELLED.name)
                    put("reserved_margin", 0.0)
                    put("exit_reason", reason.trim())
                    put("updated_at", createdAt)
                }
                check(
                    db.update(
                        SBDatabaseHelper.TABLE_PAPER_ORDERS,
                        values,
                        "id = ? AND status = ?",
                        arrayOf(
                            orderId.toString(),
                            OrderStatus.PENDING.name
                        )
                    ) == 1
                ) { "Could not cancel paper order." }
                updateAccountCapital(
                    db = db,
                    state = capital,
                    updatedAt = createdAt,
                    message = "Could not release paper capital."
                )
                insertEvent(
                    db = db,
                    orderId = orderId,
                    signalId = order.signalId,
                    type = "ENTRY_CANCELLED",
                    message = reason.trim() +
                        " (released ${formatAmount(released)})",
                    now = createdAt
                )
                db.setTransactionSuccessful()
                queryOrder(db, orderId)
            } finally {
                db.endTransaction()
            }
        }
    }

    fun saveOrder(
        symbol: String,
        exchange: String,
        side: String,
        quantity: Int,
        entryPrice: Double,
        note: String = "",
        stopLoss: Double? = null,
        target: Double? = null,
        entrySource: String = "MARKET",
        entryTimestamp: Long? = null
    ): PaperOrder {
        return maintenanceGate.withOperation {
            require(symbol.isNotBlank()) { "Symbol cannot be empty." }
            require(quantity > 0) {
                "Quantity must be greater than zero."
            }
            val maxOrderQuantity = AppConfig.get().maxOrderQuantity
            require(maxOrderQuantity <= 0 ||
                quantity <= maxOrderQuantity) {
                "Quantity exceeds the maximum allowed of $maxOrderQuantity."
            }
            require(entryPrice.isFinite() && entryPrice > 0.0) {
                "Entry price must be valid and positive."
            }
            val normalizedEntrySource =
                entrySource.trim().uppercase(Locale.ROOT)
            require(normalizedEntrySource in setOf(
                "MARKET", "MANUAL", "LEGACY"
            )) {
                "Entry source must be MARKET, MANUAL or LEGACY."
            }
            require(entryTimestamp == null || entryTimestamp > 0L) {
                "Entry timestamp must be positive."
            }

            val normalizedSide = normalizeSide(side)
            val normalizedSymbol = symbol.trim().uppercase(Locale.ROOT)
            val normalizedExchange = exchange.trim()
                .uppercase(Locale.ROOT)
            val orderValue = quantity.toDouble() * entryPrice
            require(orderValue.isFinite()) { "Order value is too large." }

            if (stopLoss != null || target != null) {
                if (stopLoss == null || target == null) {
                    throw IllegalArgumentException(
                        "Stop loss and target must be provided together."
                    )
                }
                require(stopLoss.isFinite() && stopLoss > 0.0) {
                    "Stop loss must be valid and positive."
                }
                require(target.isFinite() && target > 0.0) {
                    "Target must be valid and positive."
                }
                if (normalizedSide == OrderSide.BUY.name) {
                    require(stopLoss < entryPrice) {
                        "BUY stop loss must be below entry price."
                    }
                    require(target > entryPrice) {
                        "BUY target must be above entry price."
                    }
                } else {
                    require(stopLoss > entryPrice) {
                        "SELL stop loss must be above entry price."
                    }
                    require(target < entryPrice) {
                        "SELL target must be below entry price."
                    }
                }
            }

            val marginRate = AppConfig.get().shortMarginRate
            val reserved = if (normalizedSide == OrderSide.SELL.name) {
                orderValue * marginRate
            } else {
                0.0
            }
            val cashDebit = if (normalizedSide == OrderSide.BUY.name) {
                orderValue
            } else {
                0.0
            }

            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                val account = queryAccount(db)
                    ?: throw IllegalStateException(
                        "Set starting balance first."
                    )
                val available = account.balance - account.reservedMargin
                val required = cashDebit + reserved
                check(available + 0.000001 >= required) {
                    "Insufficient available funds. Required: ${
                        formatAmount(required)
                    }"
                }

                val maxOpenPositions = AppConfig.get().maxOpenPositions
                if (maxOpenPositions > 0) {
                    check(countOpenPositions(db) < maxOpenPositions) {
                        "Maximum open positions reached."
                    }
                }

                val now = System.currentTimeMillis()
                val orderValues = ContentValues().apply {
                    put("symbol", normalizedSymbol)
                    put("exchange", normalizedExchange)
                    put("side", normalizedSide)
                    put("quantity", quantity)
                    put("entry_price", entryPrice)
                    put("reserved_margin", reserved)
                    put("status", OrderStatus.OPEN.name)
                    put("note", note.trim())
                    put("stop_loss", stopLoss)
                    put("target", target)
                    put("entry_source", normalizedEntrySource)
                    put("entry_timestamp", entryTimestamp)
                    put("remaining_quantity", quantity)
                    put("last_price", entryPrice)
                    put("created_at", now)
                    put("updated_at", now)
                }

                val orderId = db.insertOrThrow(
                    SBDatabaseHelper.TABLE_PAPER_ORDERS,
                    null,
                    orderValues
                )

                val positionValues = ContentValues().apply {
                    put("order_id", orderId)
                    put("symbol", normalizedSymbol)
                    put("exchange", normalizedExchange)
                    put("side", normalizedSide)
                    put("quantity", quantity)
                    put("entry_price", entryPrice)
                    put("last_price", entryPrice)
                    put("reserved_margin", reserved)
                    put("status", OrderStatus.OPEN.name)
                    put("opened_at", now)
                    put("updated_at", now)
                }
                db.insertOrThrow(
                    SBDatabaseHelper.TABLE_POSITIONS,
                    null,
                    positionValues
                )

                val accountValues = ContentValues().apply {
                    put("balance", account.balance - cashDebit)
                    put(
                        "reserved_margin",
                        account.reservedMargin + reserved
                    )
                    put("updated_at", now)
                }
                check(
                    db.update(
                        SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                        accountValues,
                        "id = ?",
                        arrayOf("1")
                    ) == 1
                ) { "Could not update paper account." }

                db.setTransactionSuccessful()
                queryOrder(db, orderId)
                    ?: throw IllegalStateException(
                        "Could not read paper order."
                    )
            } finally {
                db.endTransaction()
            }
        }
    }

    fun closeOrder(orderId: Long, exitPrice: Double): PaperOrder? {
        val order = getOrder(orderId) ?: return null
        if (!order.isOpen) return null
        return exitSignalOrder(
            orderId = orderId,
            kind = PaperExecutionKind.EXIT,
            quantity = order.remainingQuantity,
            executionPrice = exitPrice,
            executedAt = System.currentTimeMillis(),
            note = "Manual exit"
        )
    }

    fun getOrder(orderId: Long): PaperOrder? {
        return maintenanceGate.withOperation {
            queryOrder(databaseHelper.readableDatabase, orderId)
        }
    }

    fun getSignalOrder(signalId: Long): PaperOrder? {
        return maintenanceGate.withOperation {
            queryOrderBySignalId(
                databaseHelper.readableDatabase,
                signalId
            )
        }
    }

    fun getSignalOrderIds(): Set<Long> {
        return maintenanceGate.withOperation {
            val result = LinkedHashSet<Long>()
            databaseHelper.readableDatabase.rawQuery(
                "SELECT signal_id FROM ${SBDatabaseHelper.TABLE_PAPER_ORDERS} " +
                    "WHERE signal_id IS NOT NULL",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(cursor.getLong(0))
                }
            }
            result
        }
    }

    fun getOrders(status: String? = null): List<PaperOrder> {
        return maintenanceGate.withOperation {
            val selection: String?
            val args: Array<String>?
            if (status == null) {
                selection = null
                args = null
            } else {
                val normalized = normalizeStatus(status)
                selection = "status = ?"
                args = arrayOf(normalized)
            }

            val result = ArrayList<PaperOrder>()
            databaseHelper.readableDatabase.query(
                SBDatabaseHelper.TABLE_PAPER_ORDERS,
                orderProjection,
                selection,
                args,
                null,
                null,
                "created_at DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(readOrder(cursor))
                }
            }
            result
        }
    }

    fun getOpenOrders(): List<PaperOrder> {
        return getOrders(OrderStatus.OPEN.name)
    }

    fun getPendingOrders(): List<PaperOrder> {
        return getOrders(OrderStatus.PENDING.name)
    }

    fun getActiveOrders(): List<PaperOrder> {
        return maintenanceGate.withOperation {
            val result = ArrayList<PaperOrder>()
            databaseHelper.readableDatabase.query(
                SBDatabaseHelper.TABLE_PAPER_ORDERS,
                orderProjection,
                "status IN (?, ?)",
                arrayOf(
                    OrderStatus.PENDING.name,
                    OrderStatus.OPEN.name
                ),
                null,
                null,
                "created_at DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(readOrder(cursor))
                }
            }
            result
        }
    }

    fun getExecutions(
        orderId: Long? = null,
        limit: Int = 200
    ): List<PaperExecution> {
        return maintenanceGate.withOperation {
            val selection = if (orderId != null) "order_id = ?" else null
            val args = if (orderId != null) {
                arrayOf(orderId.toString())
            } else {
                null
            }
            val result = ArrayList<PaperExecution>()
            databaseHelper.readableDatabase.query(
                SBDatabaseHelper.TABLE_PAPER_EXECUTIONS,
                executionProjection,
                selection,
                args,
                null,
                null,
                "executed_at DESC, id DESC",
                limit.coerceIn(1, 100000).toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(readExecution(cursor))
                }
            }
            result
        }
    }

    fun getEvents(limit: Int = 200): List<PaperTradeEvent> {
        return maintenanceGate.withOperation {
            val result = ArrayList<PaperTradeEvent>()
            databaseHelper.readableDatabase.query(
                SBDatabaseHelper.TABLE_PAPER_EVENTS,
                eventProjection,
                null,
                null,
                null,
                null,
                "created_at DESC, id DESC",
                limit.coerceIn(1, 1000).toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(readEvent(cursor))
                }
            }
            result
        }
    }

    fun logEvent(
        orderId: Long?,
        signalId: Long?,
        type: String,
        message: String,
        createdAt: Long = System.currentTimeMillis()
    ) {
        maintenanceGate.withOperation {
            insertEvent(
                db = databaseHelper.writableDatabase,
                orderId = orderId,
                signalId = signalId,
                type = type,
                message = message,
                now = createdAt
            )
        }
    }

    fun getPositions(openOnly: Boolean = true): List<Position> {
        return maintenanceGate.withOperation {
            val selection = if (openOnly) "status = ?" else null
            val args = if (openOnly) {
                arrayOf(OrderStatus.OPEN.name)
            } else {
                null
            }

            val result = ArrayList<Position>()
            val db = databaseHelper.readableDatabase
            db.query(
                SBDatabaseHelper.TABLE_POSITIONS,
                positionProjection,
                selection,
                args,
                null,
                null,
                "opened_at DESC"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(withEstimatedCharges(db, readPosition(cursor)))
                }
            }
            result
        }
    }

    fun updateMarketPrices(
        prices: Map<String, Double>,
        exchange: String? = null
    ): Int {
        return maintenanceGate.withOperation {
            if (prices.isEmpty()) return 0
            val db = databaseHelper.writableDatabase
            var updated = 0
            val now = System.currentTimeMillis()
            val normalizedExchange = exchange?.trim()
                ?.uppercase(Locale.ROOT)

            db.beginTransaction()
            try {
                prices.forEach { entry ->
                    val symbol = entry.key.trim().uppercase(Locale.ROOT)
                    val price = entry.value
                    if (symbol.isBlank() ||
                        !price.isFinite() ||
                        price <= 0.0
                    ) {
                        return@forEach
                    }

                    val values = ContentValues().apply {
                        put("last_price", price)
                        put("updated_at", now)
                    }
                    if (normalizedExchange.isNullOrBlank()) {
                        updated += db.update(
                            SBDatabaseHelper.TABLE_POSITIONS,
                            values,
                            "UPPER(symbol) = ? AND status = ?",
                            arrayOf(symbol, OrderStatus.OPEN.name)
                        )
                        db.update(
                            SBDatabaseHelper.TABLE_PAPER_ORDERS,
                            values,
                            "UPPER(symbol) = ? AND status IN (?, ?)",
                            arrayOf(
                                symbol,
                                OrderStatus.PENDING.name,
                                OrderStatus.OPEN.name
                            )
                        )
                    } else {
                        updated += db.update(
                            SBDatabaseHelper.TABLE_POSITIONS,
                            values,
                            "UPPER(symbol) = ? AND UPPER(exchange) = ? " +
                                "AND status = ?",
                            arrayOf(
                                symbol,
                                normalizedExchange,
                                OrderStatus.OPEN.name
                            )
                        )
                        db.update(
                            SBDatabaseHelper.TABLE_PAPER_ORDERS,
                            values,
                            "UPPER(symbol) = ? AND UPPER(exchange) = ? " +
                                "AND status IN (?, ?)",
                            arrayOf(
                                symbol,
                                normalizedExchange,
                                OrderStatus.PENDING.name,
                                OrderStatus.OPEN.name
                            )
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            updated
        }
    }

    fun updatePositionPrices(
        prices: Map<String, Double>,
        exchange: String? = null
    ): Int = updateMarketPrices(prices, exchange)

    fun getReservedMargin(): Double {
        return maintenanceGate.withOperation {
            getAccount()?.reservedMargin ?: 0.0
        }
    }

    fun getRealizedPnl(): Double {
        return maintenanceGate.withOperation {
            getAccount()?.realizedPnl ?: 0.0
        }
    }

    fun getUnrealizedPnl(): Double {
        return maintenanceGate.withOperation {
            var total = 0.0
            getPositions(true).forEach { position ->
                total += position.unrealizedPnl
            }
            total
        }
    }

    fun getSnapshotData(): PaperSnapshotData? {
        return maintenanceGate.withOperation {
            val db = databaseHelper.writableDatabase
            db.beginTransaction()
            try {
                val account = queryAccount(db)
                if (account == null) {
                    null
                } else {
                    val positions = queryPositions(db, true)
                    val unrealized = positions.fold(0.0) {
                            total,
                            position ->
                        total + position.unrealizedPnl
                    }
                    db.setTransactionSuccessful()
                    PaperSnapshotData(
                        account = account,
                        positions = positions,
                        realizedPnl = account.realizedPnl,
                        unrealizedPnl = unrealized
                    )
                }
            } finally {
                db.endTransaction()
            }
        }
    }

    fun getOpenPositionCount(): Int {
        return maintenanceGate.withOperation {
            databaseHelper.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_POSITIONS} " +
                    "WHERE status = ?",
                arrayOf(OrderStatus.OPEN.name)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
    }

    fun reset(startingBalance: Double): PaperAccount {
        return maintenanceGate.withOperation {
            require(startingBalance.isFinite() && startingBalance > 0.0) {
                "Starting balance must be a valid positive amount."
            }

            val db = databaseHelper.writableDatabase
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                db.delete(SBDatabaseHelper.TABLE_PAPER_EXECUTIONS, null, null)
                db.delete(SBDatabaseHelper.TABLE_PAPER_EVENTS, null, null)
                db.delete(SBDatabaseHelper.TABLE_POSITIONS, null, null)
                db.delete(SBDatabaseHelper.TABLE_PAPER_ORDERS, null, null)

                val values = ContentValues().apply {
                    put("id", 1)
                    put("starting_balance", startingBalance)
                    put("balance", startingBalance)
                    put("reserved_margin", 0.0)
                    put("realized_pnl", 0.0)
                    put("updated_at", now)
                }
                db.insertWithOnConflict(
                    SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            PaperAccount(
                startingBalance = startingBalance,
                balance = startingBalance,
                reservedMargin = 0.0,
                realizedPnl = 0.0,
                updatedAt = now
            )
        }
    }

    private fun capitalState(account: PaperAccount): PaperCapitalState {
        return PaperCapitalState(
            balance = account.balance,
            reservedMargin = account.reservedMargin,
            realizedPnl = account.realizedPnl
        )
    }

    private fun updateAccountCapital(
        db: SQLiteDatabase,
        state: PaperCapitalState,
        updatedAt: Long,
        message: String
    ) {
        val values = ContentValues().apply {
            put("balance", state.balance)
            put("reserved_margin", state.reservedMargin)
            put("realized_pnl", state.realizedPnl)
            put("updated_at", updatedAt)
        }
        check(
            db.update(
                SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
                values,
                "id = ?",
                arrayOf("1")
            ) == 1
        ) { message }
    }

    private fun sizingRules(costBpsPerSide: Double): PaperSizingRules {
        val config = AppConfig.get()
        return PaperSizingRules(
            riskPerTradePercent = config.paperRiskPerTradePercent,
            allocationPercent = config.paperAllocationPercent,
            shortMarginRate = config.shortMarginRate,
            maxQuantity = config.maxOrderQuantity,
            costBpsPerSide = costBpsPerSide
        )
    }

    private fun candidateForOrder(
        order: PaperOrder,
        referencePrice: Double
    ): PaperSignalCandidate {
        val entryLow = order.entryLow ?: minOf(
            referencePrice,
            order.entryPrice
        )
        val entryHigh = order.entryHigh ?: maxOf(
            referencePrice,
            order.entryPrice
        )
        val stopLoss = order.stopLoss ?: referencePrice
        val target1 = order.target1 ?: order.target ?: referencePrice
        val target2 = order.target2 ?: target1
        val target3 = order.target3 ?: target2
        return PaperSignalCandidate(
            signalId = order.signalId ?: 0L,
            symbol = order.symbol,
            exchange = order.exchange,
            side = order.side,
            category = order.signalCategory,
            timeframe = order.signalTimeframe,
            strategyId = order.strategyId,
            strategyVersion = order.strategyVersion,
            referencePrice = referencePrice,
            entryLow = entryLow,
            entryHigh = entryHigh,
            stopLoss = stopLoss,
            target1 = target1,
            target2 = target2,
            target3 = target3,
            generatedAt = order.signalGeneratedAt ?: order.createdAt,
            expiresAt = order.signalExpiresAt ?: Long.MAX_VALUE,
            costBpsPerSide = order.costBpsPerSide,
            slippageBpsPerSide = order.slippageBpsPerSide
        )
    }

    private fun queryOrder(db: SQLiteDatabase, orderId: Long): PaperOrder? {
        db.query(
            SBDatabaseHelper.TABLE_PAPER_ORDERS,
            orderProjection,
            "id = ?",
            arrayOf(orderId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) readOrder(cursor) else null
        }
    }

    private fun queryOrderBySignalId(
        db: SQLiteDatabase,
        signalId: Long
    ): PaperOrder? {
        db.query(
            SBDatabaseHelper.TABLE_PAPER_ORDERS,
            orderProjection,
            "signal_id = ?",
            arrayOf(signalId.toString()),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) readOrder(cursor) else null
        }
    }

    private fun queryAccount(db: SQLiteDatabase): PaperAccount? {
        db.query(
            SBDatabaseHelper.TABLE_PAPER_ACCOUNT,
            accountProjection,
            "id = ?",
            arrayOf("1"),
            null,
            null,
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) readAccount(cursor) else null
        }
    }

    private fun queryPositions(
        db: SQLiteDatabase,
        openOnly: Boolean
    ): List<Position> {
        val selection = if (openOnly) "status = ?" else null
        val args = if (openOnly) {
            arrayOf(OrderStatus.OPEN.name)
        } else {
            null
        }

        val result = ArrayList<Position>()
        db.query(
            SBDatabaseHelper.TABLE_POSITIONS,
            positionProjection,
            selection,
            args,
            null,
            null,
            "opened_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(withEstimatedCharges(db, readPosition(cursor)))
            }
        }
        return result
    }

    private fun withEstimatedCharges(
        db: SQLiteDatabase,
        position: Position
    ): Position {
        val order = queryOrder(db, position.orderId) ?: return position
        val remainingQuantity = position.quantity.coerceAtLeast(0)
        val allocatedEntryCharge = PaperSignalPolicy.allocatedEntryCharge(
            totalEntryCharge = order.entryCharges,
            originalQuantity = order.quantity,
            exitQuantity = remainingQuantity
        )
        val estimatedExitCharge = PaperSignalPolicy.exitCharge(
            price = position.lastPrice,
            quantity = remainingQuantity,
            costBps = order.costBpsPerSide
        )
        val estimate = allocatedEntryCharge + estimatedExitCharge
        return if (estimate.isFinite() && estimate >= 0.0) {
            position.copy(estimatedCharges = estimate)
        } else {
            position
        }
    }

    private fun queryPositionByOrderId(
        db: SQLiteDatabase,
        orderId: Long
    ): Position? {
        db.query(
            SBDatabaseHelper.TABLE_POSITIONS,
            positionProjection,
            "order_id = ? AND status = ?",
            arrayOf(orderId.toString(), OrderStatus.OPEN.name),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) readPosition(cursor) else null
        }
    }

    private fun countOrders(db: SQLiteDatabase): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_PAPER_ORDERS}",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun countOpenPositions(db: SQLiteDatabase): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM ${SBDatabaseHelper.TABLE_POSITIONS} " +
                "WHERE status = ?",
            arrayOf(OrderStatus.OPEN.name)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun executionExists(
        db: SQLiteDatabase,
        eventKey: String
    ): Boolean {
        db.query(
            SBDatabaseHelper.TABLE_PAPER_EXECUTIONS,
            arrayOf("id"),
            "event_key = ?",
            arrayOf(eventKey),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun insertExecution(
        db: SQLiteDatabase,
        order: PaperOrder,
        kind: PaperExecutionKind,
        quantity: Int,
        price: Double,
        grossPnl: Double,
        charges: Double,
        netPnl: Double,
        executedAt: Long,
        eventKey: String,
        note: String
    ) {
        val values = ContentValues().apply {
            put("order_id", order.id)
            put("signal_id", order.signalId)
            put("kind", kind.name)
            put("quantity", quantity)
            put("price", price)
            put("gross_pnl", grossPnl)
            put("charges", charges)
            put("net_pnl", netPnl)
            put("executed_at", executedAt)
            put("event_key", eventKey)
            put("note", note)
        }
        db.insertWithOnConflict(
            SBDatabaseHelper.TABLE_PAPER_EXECUTIONS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun insertEvent(
        db: SQLiteDatabase,
        orderId: Long?,
        signalId: Long?,
        type: String,
        message: String,
        now: Long
    ) {
        val values = ContentValues().apply {
            put("order_id", orderId)
            put("signal_id", signalId)
            put("type", type)
            put("message", message)
            put("created_at", now)
        }
        db.insert(SBDatabaseHelper.TABLE_PAPER_EVENTS, null, values)
    }

    private fun readAccount(cursor: Cursor): PaperAccount {
        return PaperAccount(
            startingBalance = cursor.getDouble(0),
            balance = cursor.getDouble(1),
            reservedMargin = cursor.getDouble(2),
            realizedPnl = cursor.getDouble(3),
            updatedAt = cursor.getLong(4)
        )
    }

    private fun readOrder(cursor: Cursor): PaperOrder {
        return PaperOrder(
            id = cursor.getLong(0),
            symbol = cursor.getString(1),
            exchange = cursor.getString(2),
            side = cursor.getString(3),
            quantity = cursor.getInt(4),
            entryPrice = cursor.getDouble(5),
            exitPrice = cursor.getDoubleOrNull(6),
            realizedPnl = cursor.getDoubleOrNull(7),
            reservedMargin = cursor.getDouble(8),
            status = cursor.getString(9),
            note = cursor.getString(10),
            createdAt = cursor.getLong(11),
            updatedAt = cursor.getLong(12),
            stopLoss = cursor.getDoubleOrNull(13),
            target = cursor.getDoubleOrNull(14),
            entrySource = cursor.getString(15),
            entryTimestamp = cursor.getLongOrNull(16),
            signalId = cursor.getLongOrNull(17),
            signalCategory = cursor.getString(18).orEmpty(),
            signalTimeframe = cursor.getString(19).orEmpty(),
            strategyId = cursor.getString(20).orEmpty(),
            strategyVersion = cursor.getString(21).orEmpty(),
            signalGeneratedAt = cursor.getLongOrNull(22),
            signalExpiresAt = cursor.getLongOrNull(23),
            entryLow = cursor.getDoubleOrNull(24),
            entryHigh = cursor.getDoubleOrNull(25),
            costBpsPerSide = cursor.getDouble(26),
            slippageBpsPerSide = cursor.getDouble(27),
            target1 = cursor.getDoubleOrNull(28),
            target2 = cursor.getDoubleOrNull(29),
            target3 = cursor.getDoubleOrNull(30),
            remainingQuantity = cursor.getInt(31),
            lastPrice = cursor.getDoubleOrNull(32),
            entryCharges = cursor.getDouble(33),
            charges = cursor.getDouble(34),
            exitReason = cursor.getString(35).orEmpty(),
            target1At = cursor.getLongOrNull(36),
            target2At = cursor.getLongOrNull(37),
            target3At = cursor.getLongOrNull(38),
            stoppedAt = cursor.getLongOrNull(39)
        )
    }

    private fun readPosition(cursor: Cursor): Position {
        return Position(
            id = cursor.getLong(0),
            orderId = cursor.getLong(1),
            symbol = cursor.getString(2),
            exchange = cursor.getString(3),
            side = cursor.getString(4),
            quantity = cursor.getInt(5),
            entryPrice = cursor.getDouble(6),
            lastPrice = cursor.getDouble(7),
            reservedMargin = cursor.getDouble(8),
            status = cursor.getString(9),
            openedAt = cursor.getLong(10),
            updatedAt = cursor.getLong(11)
        )
    }

    private fun readExecution(cursor: Cursor): PaperExecution {
        return PaperExecution(
            id = cursor.getLong(0),
            orderId = cursor.getLong(1),
            signalId = cursor.getLongOrNull(2),
            kind = PaperExecutionKind.values().firstOrNull {
                it.name == cursor.getString(3)
            } ?: PaperExecutionKind.EXIT,
            quantity = cursor.getInt(4),
            price = cursor.getDouble(5),
            grossPnl = cursor.getDouble(6),
            charges = cursor.getDouble(7),
            netPnl = cursor.getDouble(8),
            executedAt = cursor.getLong(9),
            eventKey = cursor.getString(10),
            note = cursor.getString(11).orEmpty()
        )
    }

    private fun readEvent(cursor: Cursor): PaperTradeEvent {
        return PaperTradeEvent(
            id = cursor.getLong(0),
            orderId = cursor.getLongOrNull(1),
            signalId = cursor.getLongOrNull(2),
            type = cursor.getString(3),
            message = cursor.getString(4).orEmpty(),
            createdAt = cursor.getLong(5)
        )
    }

    private fun normalizeSide(side: String): String {
        val normalized = side.trim().uppercase(Locale.ROOT)
        require(
            normalized == OrderSide.BUY.name ||
                normalized == OrderSide.SELL.name
        ) {
            "Side must be BUY or SELL."
        }
        return normalized
    }

    private fun normalizeStatus(status: String): String {
        val normalized = status.trim().uppercase(Locale.ROOT)
        require(
            normalized in OrderStatus.values().map { it.name }
        ) {
            "Unknown paper order status."
        }
        return normalized
    }

    private fun validateSignalLevels(candidate: PaperSignalCandidate) {
        val low = minOf(candidate.entryLow, candidate.entryHigh)
        val high = maxOf(candidate.entryLow, candidate.entryHigh)
        require(low.isFinite() && low > 0.0) {
            "Entry range must be valid and positive."
        }
        require(high.isFinite() && high > 0.0) {
            "Entry range must be valid and positive."
        }
        require(candidate.stopLoss.isFinite() &&
            candidate.stopLoss > 0.0) {
            "Stop loss must be valid and positive."
        }
        require(candidate.target1.isFinite() &&
            candidate.target1 > 0.0 &&
            candidate.target2.isFinite() &&
            candidate.target2 > 0.0 &&
            candidate.target3.isFinite() &&
            candidate.target3 > 0.0) {
            "Targets must be valid and positive."
        }
        if (candidate.side == OrderSide.BUY.name) {
            require(candidate.stopLoss < low) {
                "BUY stop loss must be below the entry range."
            }
            require(candidate.target1 > high &&
                candidate.target2 > candidate.target1 &&
                candidate.target3 > candidate.target2) {
                "BUY targets must be above the entry range and ordered."
            }
        } else {
            require(candidate.stopLoss > high) {
                "SELL stop loss must be above the entry range."
            }
            require(candidate.target1 < low &&
                candidate.target2 < candidate.target1 &&
                candidate.target3 < candidate.target2) {
                "SELL targets must be below the entry range and ordered."
            }
        }
    }

    private fun formatAmount(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun Cursor.getLongOrNull(index: Int): Long? {
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.getDoubleOrNull(index: Int): Double? {
        return if (isNull(index)) null else getDouble(index)
    }

    companion object {
        private val accountProjection = arrayOf(
            "starting_balance",
            "balance",
            "reserved_margin",
            "realized_pnl",
            "updated_at"
        )

        private val orderProjection = arrayOf(
            "id",
            "symbol",
            "exchange",
            "side",
            "quantity",
            "entry_price",
            "exit_price",
            "realized_pnl",
            "reserved_margin",
            "status",
            "note",
            "created_at",
            "updated_at",
            "stop_loss",
            "target",
            "entry_source",
            "entry_timestamp",
            "signal_id",
            "signal_category",
            "signal_timeframe",
            "strategy_id",
            "strategy_version",
            "signal_generated_at",
            "signal_expires_at",
            "entry_low",
            "entry_high",
            "cost_bps_per_side",
            "slippage_bps_per_side",
            "target1",
            "target2",
            "target3",
            "remaining_quantity",
            "last_price",
            "entry_charges",
            "charges",
            "exit_reason",
            "target1_at",
            "target2_at",
            "target3_at",
            "stopped_at"
        )

        private val positionProjection = arrayOf(
            "id",
            "order_id",
            "symbol",
            "exchange",
            "side",
            "quantity",
            "entry_price",
            "last_price",
            "reserved_margin",
            "status",
            "opened_at",
            "updated_at"
        )

        private val executionProjection = arrayOf(
            "id",
            "order_id",
            "signal_id",
            "kind",
            "quantity",
            "price",
            "gross_pnl",
            "charges",
            "net_pnl",
            "executed_at",
            "event_key",
            "note"
        )

        private val eventProjection = arrayOf(
            "id",
            "order_id",
            "signal_id",
            "type",
            "message",
            "created_at"
        )
    }
}
