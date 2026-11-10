package com.sb

import android.app.Activity
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.sb.shizuku.IShizukuShellService
import rikka.shizuku.Shizuku

object ShizukuShellBridge {
    private const val REQUEST_CODE = 7301
    @Volatile private var remote: IShizukuShellService? = null
    private var callback: ((String) -> Unit)? = null
    private var listenerAdded = false

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bind()
                } else {
                    callback?.invoke("Shizuku permission denied")
                }
            }
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remote = IShizukuShellService.Stub.asInterface(service)
            callback?.invoke("Connected • Shizuku UID: ${Shizuku.getUid()}")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
            callback?.invoke("Shizuku service disconnected")
        }
    }

    fun connect(activity: Activity, result: (String) -> Unit) {
        callback = result
        if (!Shizuku.pingBinder()) {
            result("Shizuku is not running")
            return
        }
        if (!listenerAdded) {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            listenerAdded = true
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bind()
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
            result("Permission request sent — approve SB in Shizuku")
        }
    }

    private fun bind() {
        try {
            val args = Shizuku.UserServiceArgs(
                ComponentName("com.sb", ShizukuShellUserService::class.java.name)
            ).tag("sb-shell")
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(1)
            Shizuku.bindUserService(args, connection)
            callback?.invoke("Starting Shizuku shell service…")
        } catch (error: Exception) {
            callback?.invoke("Shizuku error: ${error.message}")
        }
    }

    fun execute(command: String, result: (String) -> Unit) {
        Thread {
            try {
                val service = remote ?: throw IllegalStateException("Connect Shizuku first")
                result(service.execute(command))
            } catch (error: Exception) {
                result("Command failed: ${error.message}")
            }
        }.start()
    }
}
