
package com.sb

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.widget.*
import com.sb.backend.security.KotakNeoSecureStore
import com.sb.backend.services.KotakNeoAuthClient
import com.sb.backend.services.KotakNeoMarketDataService
import com.sb.backend.services.KotakSessionCoordinator
import com.sb.backend.core.BackendManager
import com.sb.ui.theme.AppDesign
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class SettingsScreen(private val context: Context) {

    private val secureStore = KotakNeoSecureStore(context)

    private val bg = AppDesign.background
    private val panel = AppDesign.field
    private val yellow = AppDesign.accent
    private val white = AppDesign.textPrimary
    private val grey = AppDesign.textMuted

    private lateinit var statusText: TextView

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun label(
        parent: LinearLayout,
        value: String,
        size: Float = 13f,
        color: Int = white,
        bold: Boolean = false
    ) {
        parent.addView(TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(5), 0, dp(5))
        })
    }

    private fun input(
        hintText: String,
        value: String = "",
        type: Int = android.text.InputType.TYPE_CLASS_TEXT
    ) = EditText(context).apply {
        hint = hintText
        setText(value)
        setTextColor(white)
        setHintTextColor(grey)
        inputType = type
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = AppDesign.fieldBackground(context, 9)
    }

    private fun actionButton(
        title: String,
        action: () -> Unit
    ) = Button(context).apply {
        text = title
        isAllCaps = false
        textSize = 12f
        minHeight = 0
        minimumHeight = 0
        setTextColor(white)
        background = AppDesign.roundedBackground(context, panel, 9)
        setOnClickListener { action() }
    }

    fun createView(): ScrollView {
        val scroll = ScrollView(context).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
        }

        scroll.addView(body)

        label(body, "Settings", 19f, white, true)
        label(body, "Kotak Neo Broker Configuration", 11f, grey)

        label(body, "Android Shell (Shizuku)", 16f, yellow, true)
        val shizukuStatus = TextView(context).apply {
            text = "Not connected"
            textSize = 12f
            setTextColor(grey)
            setPadding(0, dp(4), 0, dp(8))
        }
        body.addView(shizukuStatus)

        body.addView(actionButton("Connect Shizuku") {
            val activity = context as? android.app.Activity
            if (activity == null) {
                shizukuStatus.text = "Open Settings from the SB app"
            } else {
                com.sb.ShizukuShellBridge.connect(activity) { message ->
                    android.os.Handler(context.mainLooper).post {
                        shizukuStatus.text = message
                    }
                }
            }
        })

        body.addView(actionButton("Test Shell (id)") {
            com.sb.ShizukuShellBridge.execute("id") { result ->
                android.os.Handler(context.mainLooper).post {
                    shizukuStatus.text = result
                }
            }
        })

        label(body, "API Access Token", 12f, yellow, true)
        val tokenInput = input(
            "Kotak Neo API Access Token",
            secureStore.getAccessToken(),
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        body.addView(tokenInput)

        label(body, "Registered Mobile Number", color = yellow, bold = true)
        val mobileInput = input(
            "Registered Mobile Number",
            secureStore.getMobile(),
            android.text.InputType.TYPE_CLASS_PHONE
        )
        body.addView(mobileInput)

        label(body, "UCC / Client Code", color = yellow, bold = true)
        val uccInput = input(
            "Kotak Client Code",
            secureStore.getUcc()
        )
        body.addView(uccInput)

        label(body, "TOTP Secret", color = yellow, bold = true)
        val totpInput = input(
            "Base32 Authenticator Secret",
            secureStore.getTotpSecret(),
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        body.addView(totpInput)

        label(body, "Kotak Neo MPIN", color = yellow, bold = true)
        val mpinInput = input(
            "6-digit MPIN",
            secureStore.getMpin(),
            android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        )
        body.addView(mpinInput)

        label(body, "Trading Mode", color = yellow, bold = true)
        label(body, "PAPER only — LIVE trading remains locked.", 13f, grey)

        KotakSessionCoordinator.initialize(context)

        statusText = TextView(context).apply {
            text = KotakSessionCoordinator.currentStatus()
            textSize = 14f
            setTextColor(grey)
            setPadding(0, dp(14), 0, dp(14))
        }
        body.addView(statusText)

        KotakSessionCoordinator.setStatusListener { message ->
            updateLiveFeedStatus(message)
        }

        body.addView(actionButton("Save Kotak Configuration") {
            saveConfiguration(
                tokenInput.text.toString(),
                mobileInput.text.toString(),
                uccInput.text.toString(),
                totpInput.text.toString(),
                mpinInput.text.toString()
            )
        })

        body.addView(actionButton("Test Manual Kotak Login") {
            manualLogin(
                tokenInput.text.toString().trim(),
                mobileInput.text.toString().trim(),
                uccInput.text.toString().trim()
            )
        })

        label(body, "Automatic Login", 18f, yellow, true)

        val autoSwitch = Switch(context).apply {
            text = "Auto Login ON / OFF"
            setTextColor(white)
            isChecked = secureStore.isAutoLoginEnabled()
        }
        body.addView(autoSwitch)

        autoSwitch.setOnCheckedChangeListener { _, enabled ->
            if (enabled) {
                try {
                    saveConfiguration(
                        tokenInput.text.toString(),
                        mobileInput.text.toString(),
                        uccInput.text.toString(),
                        totpInput.text.toString(),
                        mpinInput.text.toString()
                    )

                    KotakSessionCoordinator.initialize(context)
                    KotakSessionCoordinator.startAutoLogin()
                } catch (e: Exception) {
                    autoSwitch.isChecked = false
                    statusText.text = "Setup error: ${e.message}"
                    statusText.setTextColor(Color.RED)
                }
            } else {
                KotakSessionCoordinator.stopAutoLogin()
            }
        }

        body.addView(actionButton("Test Live Quote — RELIANCE") {
            testRelianceQuote()
        })

        body.addView(actionButton("Sync Kotak Scrip Master") {
            syncKotakScripMaster()
        })

        body.addView(actionButton("Connect Kotak Live Feed") {
            connectLiveFeed()
        })

        body.addView(actionButton("Disconnect Kotak Live Feed") {
            disconnectLiveFeed()
        })

        body.addView(actionButton("Clear Saved Kotak Credentials") {
            AlertDialog.Builder(context)
                .setTitle("Clear Kotak Credentials?")
                .setMessage("Saved credentials and session will be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    KotakSessionCoordinator.clear()
                }
                .create()
                .also { dialog ->
                    AppDesign.styleDialog(dialog)
                    dialog.show()
                }
        })

        return scroll
    }

    private fun saveConfiguration(
        token: String,
        mobile: String,
        ucc: String,
        totpSecret: String,
        mpin: String
    ) {
        try {
            require(token.isNotBlank()) { "API Access Token missing" }
            require(mobile.isNotBlank()) { "Mobile number missing" }
            require(ucc.isNotBlank()) { "UCC missing" }
            require(totpSecret.isNotBlank()) { "TOTP Secret missing" }
            require(mpin.matches(Regex("\\d{6}"))) {
                "MPIN must be 6 digits"
            }

            secureStore.save(token.trim(), mobile.trim(), ucc.trim())
            secureStore.saveTotpSecret(totpSecret.trim())
            secureStore.saveMpin(mpin.trim())

            statusText.text = "Credentials saved securely. Not authenticated yet."
            statusText.setTextColor(yellow)

        } catch (e: Exception) {
            statusText.text = "Save failed: ${e.message}"
            statusText.setTextColor(Color.RED)
        }
    }

    private fun manualLogin(
        token: String,
        mobile: String,
        ucc: String
    ) {
        if (token.isBlank() || mobile.isBlank() || ucc.isBlank()) {
            statusText.text = "Save API Token, Mobile and UCC first."
            return
        }

        val totpEntry = input("Current 6-digit TOTP")
        AlertDialog.Builder(context)
            .setTitle("Kotak Neo TOTP")
            .setView(totpEntry)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val totp = totpEntry.text.toString().trim()
                if (!totp.matches(Regex("\\d{6}"))) {
                    statusText.text = "Invalid TOTP format"
                    return@setPositiveButton
                }

                statusText.text = "TOTP login in progress..."

                thread {
                    try {
                        val client = KotakNeoAuthClient()
                        val viewSession = client.loginWithTotp(
                            token, mobile, ucc, totp
                        )

                        android.os.Handler(context.mainLooper).post {
                            val mpinEntry = input("6-digit MPIN")
                            AlertDialog.Builder(context)
                                .setTitle("Kotak Neo MPIN")
                                .setView(mpinEntry)
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Validate") { _, _ ->
                                    val mpin = mpinEntry.text.toString().trim()
                                    if (!mpin.matches(Regex("\\d{6}"))) {
                                        statusText.text = "Invalid MPIN"
                                        return@setPositiveButton
                                    }

                                    statusText.text = "Validating MPIN..."
                                    thread {
                                        try {
                                            val session = client.validateMpin(
                                                token, viewSession, mpin
                                            )
                                            secureStore.saveTradeSession(
                                                session.token,
                                                session.sid,
                                                session.baseUrl,
                                                session.feedUrl
                                            )

                                            android.os.Handler(context.mainLooper).post {
                                                KotakSessionCoordinator.initialize(
                                                    context
                                                )
                                                KotakSessionCoordinator.syncScripMaster(
                                                    forceReconnect = true
                                                )
                                            }
                                        } catch (e: Exception) {
                                            android.os.Handler(context.mainLooper).post {
                                                statusText.text =
                                                    "MPIN validation failed: ${e.message}"
                                                statusText.setTextColor(Color.RED)
                                            }
                                        }
                                    }
                                }
                                .create()
                                .also { dialog ->
                                    AppDesign.styleDialog(dialog)
                                    dialog.show()
                                }
                        }
                    } catch (e: Exception) {
                        android.os.Handler(context.mainLooper).post {
                            statusText.text = "TOTP login failed: ${e.message}"
                            statusText.setTextColor(Color.RED)
                        }
                    }
                }
            }
            .create()
            .also { dialog ->
                AppDesign.styleDialog(dialog)
                dialog.show()
            }
    }

    private fun syncKotakScripMaster() {
        KotakSessionCoordinator.initialize(context)
        KotakSessionCoordinator.syncScripMaster(
            forceReconnect = true
        )
    }

    private fun connectLiveFeed() {
        KotakSessionCoordinator.initialize(context)
        KotakSessionCoordinator.connectFeed()
    }

    private fun disconnectLiveFeed() {
        KotakSessionCoordinator.disconnectFeed()
    }

    private fun updateLiveFeedStatus(message: String) {
        android.os.Handler(context.mainLooper).post {
            val lower = message.toLowerCase(Locale.US)

            statusText.text = message
            statusText.setTextColor(
                when {
                    lower.contains("failed") ||
                        lower.contains("error") -> Color.RED
                    lower.contains("authenticated") ||
                        lower.contains("live ticks") -> Color.GREEN
                    else -> grey
                }
            )
        }
    }

    private fun testRelianceQuote() {
        statusText.text = "Fetching RELIANCE quote..."

        thread {
            try {
                // Backend startup is owned by MarketForegroundService.
                // SettingsScreen must not initialize BackendManager again.
                if (!BackendManager.isReady()) {
                    throw Exception("Backend is not ready yet")
                }

                val service = KotakNeoMarketDataService(
                    secureStore,
                    BackendManager.market()
                )
                val quote = service.fetchRelianceQuote()

                val priceFormat = DecimalFormat("#,##0.00")
                val exchangeTime = quote.exchangeTimestamp?.let {
                    SimpleDateFormat(
                        "dd-MM-yyyy hh:mm:ss a",
                        Locale.getDefault()
                    ).format(Date(it))
                } ?: "N/A"

                val receivedTime = SimpleDateFormat(
                    "dd-MM-yyyy hh:mm:ss a",
                    Locale.getDefault()
                ).format(Date(quote.timestamp))

                android.os.Handler(context.mainLooper).post {
                    statusText.text =
                        "LIVE QUOTE SAVED\n" +
                        "Symbol: ${quote.symbol}\n" +
                        "Exchange: ${quote.exchange}\n" +
                        "LTP: ₹${priceFormat.format(quote.ltp)}\n" +
                        "Open: ${quote.open}\n" +
                        "High: ${quote.high}\n" +
                        "Low: ${quote.low}\n" +
                        "Previous Close: ${quote.previousClose}\n" +
                        "Change: ${quote.change}\n" +
                        "Change %: ${quote.changePercent}\n" +
                        "Volume: ${quote.volume}\n" +
                        "Exchange Time: $exchangeTime\n" +
                        "Received Time: $receivedTime\n" +
                        "Database: sb_backend.db"
                    statusText.setTextColor(Color.GREEN)
                }

            } catch (e: Exception) {
                android.os.Handler(context.mainLooper).post {
                    statusText.text = "Quote Error: ${e.message}"
                    statusText.setTextColor(Color.RED)
                }
            }
        }
    }

    fun close() {
        KotakSessionCoordinator.setStatusListener(null)
    }
}
