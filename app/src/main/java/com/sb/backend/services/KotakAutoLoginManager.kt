package com.sb.backend.services

import android.os.Handler
import android.os.Looper
import com.sb.backend.security.KotakNeoSecureStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

class KotakAutoLoginManager(
    private val secureStore: KotakNeoSecureStore,
    private val authClient: KotakNeoAuthClient,
    private val onStatus: (String) -> Unit,
    private val onLoginSuccess: (KotakNeoAuthClient.TradeSession) -> Unit,
    private val onLoginError: (String) -> Unit
) {

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val running = AtomicBoolean(false)

    @Volatile
    private var loginTask: Future<*>? = null

    private fun status(message: String) {
        mainHandler.post {
            onStatus(message)
        }
    }

    private fun success(session: KotakNeoAuthClient.TradeSession) {
        mainHandler.post {
            onLoginSuccess(session)
        }
    }

    private fun error(message: String) {
        mainHandler.post {
            onLoginError(message)
        }
    }

    fun startAutoLogin() {
        if (!running.compareAndSet(false, true)) {
            status("Auto Login already running")
            return
        }

        secureStore.setAutoLoginEnabled(true)
        loginNow()
    }

    fun loginNow() {
        if (!running.get()) {
            status("Auto Login is OFF")
            return
        }

        if (loginTask?.isDone == false) {
            status("Login already in progress")
            return
        }

        loginTask = executor.submit {
            try {
                status("Checking saved Kotak credentials...")

                val accessToken = secureStore.getAccessToken()
                val mobile = secureStore.getMobile()
                val ucc = secureStore.getUcc()
                val totpSecret = secureStore.getTotpSecret()
                val mpin = secureStore.getMpin()

                require(accessToken.isNotBlank()) {
                    "ERROR | API Access Token is missing"
                }

                require(mobile.isNotBlank()) {
                    "ERROR | Mobile number is missing"
                }

                require(ucc.isNotBlank()) {
                    "ERROR | UCC is missing"
                }

                require(totpSecret.isNotBlank()) {
                    "ERROR | TOTP Secret is missing"
                }

                require(mpin.matches(Regex("\\d{6}"))) {
                    "ERROR | MPIN is missing or invalid"
                }

                if (!running.get()) return@submit

                status("Generating TOTP...")

                val totp = KotakTotpGenerator.generate(totpSecret)

                if (!running.get()) return@submit

                status("Kotak TOTP login in progress...")

                val viewSession = authClient.loginWithTotp(
                    accessToken = accessToken,
                    mobile = mobile,
                    ucc = ucc,
                    totp = totp
                )

                if (!running.get()) return@submit

                status("Validating MPIN...")

                val tradeSession = authClient.validateMpin(
                    accessToken = accessToken,
                    viewSession = viewSession,
                    mpin = mpin
                )

                if (!running.get()) return@submit

                secureStore.saveTradeSession(
                    token = tradeSession.token,
                    sid = tradeSession.sid,
                    baseUrl = tradeSession.baseUrl,
                    feedUrl = tradeSession.feedUrl
                )

                status("Kotak Neo login successful")
                success(tradeSession)

            } catch (e: Exception) {
                if (running.get()) {
                    status("Kotak Neo login failed")
                    error(e.message ?: "Authentication failed")
                }
            }
        }
    }

    fun stopAutoLogin() {
        running.set(false)
        secureStore.setAutoLoginEnabled(false)

        loginTask?.cancel(true)
        loginTask = null

        status("Auto Login OFF")
    }

    fun isRunning(): Boolean = running.get()

    fun close() {
        stopAutoLogin()
        executor.shutdownNow()
    }
}
