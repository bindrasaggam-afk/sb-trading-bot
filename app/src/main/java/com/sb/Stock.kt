package com.sb

import java.io.Serializable

data class Stock(
    val id: Long = 0L,
    val exchange: String = "",
    val symbol: String,
    val name: String,
    val isin: String? = null,
    val instrumentType: String = "EQUITY"
) : Serializable