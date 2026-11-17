package com.sb.backend.trading

import com.sb.backend.models.OrderSide

data class PaperCapitalState(
    val balance: Double,
    val reservedMargin: Double,
    val realizedPnl: Double
) {
    val availableBalance: Double
        get() = balance - reservedMargin
}

object PaperCapitalPolicy {

    private const val EPSILON = 0.000001

    fun reserve(
        state: PaperCapitalState,
        amount: Double
    ): PaperCapitalState {
        require(amount.isFinite() && amount >= 0.0) {
            "Reservation amount must be valid and non-negative."
        }
        require(state.availableBalance + EPSILON >= amount) {
            "Insufficient available capital for reservation."
        }
        return state.copy(
            reservedMargin = state.reservedMargin + amount
        )
    }

    fun release(
        state: PaperCapitalState,
        amount: Double
    ): PaperCapitalState {
        require(amount.isFinite() && amount >= 0.0) {
            "Release amount must be valid and non-negative."
        }
        require(state.reservedMargin + EPSILON >= amount) {
            "Cannot release more capital than is reserved."
        }
        return state.copy(
            reservedMargin = (state.reservedMargin - amount)
                .coerceAtLeast(0.0)
        )
    }

    fun applyEntry(
        state: PaperCapitalState,
        previousReservation: Double,
        requirement: PaperCapitalRequirement
    ): PaperCapitalState {
        require(previousReservation.isFinite() &&
            previousReservation >= 0.0) {
            "Previous reservation must be valid and non-negative."
        }
        require(requirement.totalReservation.isFinite() &&
            requirement.totalReservation >= 0.0) {
            "Entry requirement must be valid and non-negative."
        }
        require(state.reservedMargin + EPSILON >= previousReservation) {
            "Order reservation is missing from the paper account."
        }

        val next = PaperCapitalState(
            balance = state.balance -
                requirement.cashDebit -
                requirement.entryCharge,
            reservedMargin = state.reservedMargin -
                previousReservation +
                requirement.reservedMargin,
            realizedPnl = state.realizedPnl - requirement.entryCharge
        )
        check(next.reservedMargin >= -EPSILON) {
            "Paper reserved capital became negative at entry."
        }
        check(next.availableBalance >= -EPSILON) {
            "Insufficient available capital at entry."
        }
        return next.copy(reservedMargin = next.reservedMargin.coerceAtLeast(0.0))
    }

    fun applyExit(
        state: PaperCapitalState,
        side: String,
        exitPrice: Double,
        exitQuantity: Int,
        grossPnl: Double,
        exitCharge: Double,
        releaseReserved: Double
    ): PaperCapitalState {
        require(exitPrice.isFinite() && exitPrice > 0.0) {
            "Exit price must be valid and positive."
        }
        require(exitQuantity > 0) {
            "Exit quantity must be positive."
        }
        require(grossPnl.isFinite() && exitCharge.isFinite() &&
            releaseReserved.isFinite() && releaseReserved >= 0.0) {
            "Exit amounts must be valid."
        }
        require(state.reservedMargin + EPSILON >= releaseReserved) {
            "Cannot release more capital than is reserved at exit."
        }

        val cashChange = if (side == OrderSide.BUY.name) {
            exitPrice * exitQuantity - exitCharge
        } else {
            grossPnl - exitCharge
        }
        val next = PaperCapitalState(
            balance = state.balance + cashChange,
            reservedMargin = (state.reservedMargin - releaseReserved)
                .coerceAtLeast(0.0),
            realizedPnl = state.realizedPnl + grossPnl - exitCharge
        )
        check(next.availableBalance >= -EPSILON) {
            "Paper available capital became negative at exit."
        }
        return next
    }

    fun entryExecutionNetPnl(): Double = 0.0

    fun exitExecutionNetPnl(
        grossPnl: Double,
        allocatedEntryCharge: Double,
        exitCharge: Double
    ): Double {
        require(grossPnl.isFinite() &&
            allocatedEntryCharge.isFinite() &&
            exitCharge.isFinite()) {
            "P&L components must be valid."
        }
        return grossPnl - allocatedEntryCharge - exitCharge
    }
}
