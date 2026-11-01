package com.sb.backend.models

data class Instrument(
    val id: Long = 0L,
    val symbol: String,
    val name: String,
    val exchange: String,
    val segment: String,
    val instrumentType: String,
    val tradingSymbol: String = symbol,
    val token: String? = null,
    val lotSize: Int = 1,
    val tickSize: Double = 0.05,
    val isActive: Boolean = true
) {
    init {
        require(symbol.isNotBlank()) { "Symbol cannot be empty" }
        require(exchange.isNotBlank()) { "Exchange cannot be empty" }
        require(lotSize > 0) { "Lot size must be positive" }
        require(tickSize > 0.0) { "Tick size must be positive" }
    }
}
