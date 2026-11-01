package com.sb

import android.content.Context

class StockRepository(context: Context) {

    private val appContext = context.applicationContext
    private val dbHelper = StockMasterDbHelper(appContext)

    fun importStockMaster(assetName: String = "stock_master.csv"): Int {
        return dbHelper.importCsv(appContext, assetName)
    }

    fun searchStocks(query: String, limit: Int = 100): List<Stock> {
        return dbHelper.search(query, limit)
    }

    fun getStockCount(): Int {
        return dbHelper.getStockCount()
    }

    fun close() {
        dbHelper.close()
    }
}