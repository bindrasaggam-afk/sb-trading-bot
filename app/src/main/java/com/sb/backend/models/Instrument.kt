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
    val isActive: Boolean = true,
    val expiryMillis: Long? = null,
    val exchangeSegment: String = "",
    val category: String = "",
    val subcategory: String = "",
    val rawSymbol: String = "",
    val isin: String? = null,
    val description: String? = null,
    val groupCode: String? = null,
    val assetCode: String? = null,
    val amcCode: String? = null,
    val issueMillis: Long? = null,
    val maturityMillis: Long? = null,
    val caseEligible: Boolean = false,
    val sourceTickSize: Double? = null,
    val source: String = "",
    val sourceUpdatedAt: Long? = null,
    val isAlias: Boolean = false,
    val aliasOf: String? = null,
    val feedName: String? = null
) {
    init {
        require(id >= 0L) {
            "Instrument ID cannot be negative"
        }

        require(symbol.isNotBlank()) {
            "Symbol cannot be empty"
        }

        require(name.isNotBlank()) {
            "Instrument name cannot be empty"
        }

        require(exchange.isNotBlank()) {
            "Exchange cannot be empty"
        }

        require(segment.isNotBlank()) {
            "Segment cannot be empty"
        }

        require(instrumentType.isNotBlank()) {
            "Instrument type cannot be empty"
        }

        require(tradingSymbol.isNotBlank()) {
            "Trading symbol cannot be empty"
        }

        require(token == null || token.isNotBlank()) {
            "Token cannot be blank"
        }

        require(lotSize > 0) {
            "Lot size must be positive"
        }

        require(tickSize.isFinite() && tickSize > 0.0) {
            "Tick size must be finite and positive"
        }

        require(expiryMillis == null || expiryMillis > 0L) {
            "Expiry timestamp must be positive"
        }

        require(issueMillis == null || issueMillis > 0L) {
            "Issue timestamp must be positive"
        }

        require(maturityMillis == null || maturityMillis > 0L) {
            "Maturity timestamp must be positive"
        }

        require(sourceUpdatedAt == null || sourceUpdatedAt > 0L) {
            "Source update timestamp must be positive"
        }
    }
}
