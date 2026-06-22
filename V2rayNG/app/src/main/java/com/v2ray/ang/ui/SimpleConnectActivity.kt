package com.v2ray.ang.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimpleConnectActivity : BaseActivity() {

    private lateinit var btnConnect: Button
    private lateinit var tvStatus: TextView

    private val configsUrl = "https://raw.githubusercontent.com/alirezazahedikermani/v2rayExtractor/refs/heads/main/scripts/test.txt"
    private val autoSubId = "__auto_connect_sub__"

    // Sorted list of (guid, delay) pairs, best first
    private var sortedConfigs: List<Pair<String, Long>> = emptyList()
    private var currentConfigIndex = 0
    private var isConnecting = false
    private var watchdogJob: Job? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            connectToNextConfig()
        } else {
            setIdleState("برای اتصال نیاز به مجوز VPN است")
        }
    }

    private val msgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_START_SUCCESS -> {
                    watchdogJob?.cancel()
                    setConnectedState()
                }
                AppConfig.MSG_STATE_START_FAILURE -> {
                    watchdogJob?.cancel()
                    val error = intent.getStringExtra("content")
                    tryNextConfig("خطا در اتصال: ${error.orEmpty()}")
                }
                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    setIdleState("")
                }
                AppConfig.MSG_STATE_RUNNING -> {
                    watchdogJob?.cancel()
                    setConnectedState()
                }
                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    if (!isConnecting) setIdleState("")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_connect)

        btnConnect = findViewById(R.id.btn_connect)
        tvStatus = findViewById(R.id.tv_status)

        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(this, msgReceiver, filter, Utils.receiverFlags())

        btnConnect.setOnClickListener { handleButtonClick() }

        // Check if already running
        if (CoreServiceManager.isRunning()) {
            setConnectedState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        unregisterReceiver(msgReceiver)
    }

    private fun handleButtonClick() {
        if (CoreServiceManager.isRunning()) {
            // Disconnect
            CoreServiceManager.stopVService(this)
            btnConnect.isEnabled = false
            tvStatus.text = "در حال قطع اتصال..."
        } else {
            startConnectFlow()
        }
    }

    private fun startConnectFlow() {
        isConnecting = true
        currentConfigIndex = 0
        btnConnect.isEnabled = false
        tvStatus.text = "در حال دریافت تنظیمات..."

        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                HttpUtil.getUrlContent(UrlContentRequest(url = configsUrl, timeout = 15000))
            }

            if (content.isNullOrBlank()) {
                setIdleState("دریافت تنظیمات ناموفق بود")
                return@launch
            }

            tvStatus.text = "در حال پردازش تنظیمات..."

            // Clear old auto-connect configs and import new ones
            withContext(Dispatchers.IO) {
                val oldList = MmkvManager.decodeServerList(autoSubId)
                for (guid in oldList) {
                    MmkvManager.removeServer(guid)
                }
            }

            val (count, _) = withContext(Dispatchers.IO) {
                AngConfigManager.importBatchConfig(content, autoSubId, false)
            }

            if (count <= 0) {
                setIdleState("هیچ تنظیماتی یافت نشد")
                return@launch
            }

            tvStatus.text = "در حال آزمایش $count تنظیمات..."

            val guids = withContext(Dispatchers.IO) {
                MmkvManager.decodeServerList(autoSubId)
            }

            // TCP ping test all configs concurrently
            val results = withContext(Dispatchers.IO) {
                val pingResults = mutableListOf<Pair<String, Long>>()
                val jobs = guids.map { guid ->
                    launch {
                        val profile = MmkvManager.decodeServerConfig(guid)
                        val server = profile?.server
                        val port = profile?.serverPort?.toIntOrNull()
                        val delay = if (server != null && port != null) {
                            SpeedtestManager.tcping(server, port)
                        } else {
                            -1L
                        }
                        synchronized(pingResults) {
                            pingResults.add(guid to delay)
                        }
                    }
                }
                jobs.forEach { it.join() }
                pingResults
            }

            // Sort: valid delays first (ascending), then failed ones
            sortedConfigs = results.sortedWith(compareBy { if (it.second > 0) it.second else Long.MAX_VALUE })

            val validCount = sortedConfigs.count { it.second > 0 }
            if (validCount == 0) {
                setIdleState("هیچ تنظیمات معتبری پاسخ نداد")
                return@launch
            }

            tvStatus.text = "$validCount تنظیمات معتبر یافت شد"
            currentConfigIndex = 0
            requestVpnAndConnect()
        }
    }

    private fun requestVpnAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            connectToNextConfig()
        } else {
            requestVpnPermission.launch(intent)
        }
    }

    private fun connectToNextConfig() {
        val config = sortedConfigs.getOrNull(currentConfigIndex)
        if (config == null) {
            setIdleState("اتصال به هیچ تنظیماتی ممکن نبود")
            return
        }

        val (guid, delay) = config
        val profile = MmkvManager.decodeServerConfig(guid)
        tvStatus.text = "در حال اتصال به ${profile?.remarks ?: "سرور"} (${if (delay > 0) "${delay}ms" else "نامعلوم"})..."

        MmkvManager.setSelectServer(guid)
        CoreServiceManager.startVService(this, guid)

        // 15-second watchdog: if no success/failure received, try next
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(15_000)
            if (isConnecting) {
                CoreServiceManager.stopVService(this@SimpleConnectActivity)
                delay(1000)
                tryNextConfig("عدم پاسخ در ۱۵ ثانیه")
            }
        }
    }

    private fun tryNextConfig(reason: String) {
        currentConfigIndex++
        val remaining = sortedConfigs.size - currentConfigIndex
        if (remaining <= 0) {
            setIdleState("اتصال ناموفق بود: $reason")
            return
        }
        tvStatus.text = "$reason - تلاش با تنظیمات بعدی ($remaining باقی مانده)..."
        connectToNextConfig()
    }

    private fun setConnectedState() {
        isConnecting = false
        btnConnect.isEnabled = true
        btnConnect.text = "قطع کردن"
        btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.md_theme_tertiary)
        val serverName = CoreServiceManager.getRunningServerName()
        tvStatus.text = if (serverName.isNotEmpty()) "متصل به: $serverName" else "متصل"
    }

    private fun setIdleState(message: String) {
        isConnecting = false
        watchdogJob?.cancel()
        btnConnect.isEnabled = true
        btnConnect.text = "اتصال"
        btnConnect.backgroundTintList = ContextCompat.getColorStateList(this, R.color.md_theme_secondary)
        tvStatus.text = message
    }
}
