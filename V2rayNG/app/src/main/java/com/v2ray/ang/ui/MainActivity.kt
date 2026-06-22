package com.v2ray.ang.ui

import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : BaseActivity() {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private var pendingConnect = false

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_SORT_AFTER_TEST, true)
        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)

        binding.btnConnect.setOnClickListener { handleConnectClick() }
        binding.btnNext.setOnClickListener { handleNextClick() }

        setupViewModel()
        mainViewModel.reloadServerList()
    }

    private fun setupViewModel() {
        mainViewModel.isRunning.observe(this) { isRunning ->
            if (isRunning == true) {
                binding.btnConnect.text = "Disconnect"
                binding.tvStatus.text = "Connected"
            } else {
                binding.btnConnect.text = "Connect"
                binding.tvStatus.text = "Not Connected"
            }
        }

        mainViewModel.testFinishedAction.observe(this) {
            binding.pbLoading.isVisible = false
            if (pendingConnect) {
                pendingConnect = false
                val bestServer = mainViewModel.serversCache.firstOrNull()
                if (bestServer != null) {
                    MmkvManager.setSelectServer(bestServer.guid)
                    startV2Ray()
                } else {
                    toast("No valid servers found")
                }
            }
        }

        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun handleConnectClick() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        } else {
            binding.pbLoading.isVisible = true
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val configUrl = "https://raw.githubusercontent.com/alirezazahedikermani/v2rayExtractor/refs/heads/main/scripts/test.txt"
                    val content = HttpUtil.getUrlContentWithUserAgent(UrlContentRequest(configUrl))
                    if (content.isNotEmpty()) {
                        mainViewModel.removeAllServer()
                        AngConfigManager.importBatchConfig(content, "", true)
                        withContext(Dispatchers.Main) {
                            mainViewModel.subscriptionIdChanged("")
                            mainViewModel.reloadServerList()
                            pendingConnect = true
                            mainViewModel.testAllRealPing()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.pbLoading.isVisible = false
                            toast("Failed to download configs")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.pbLoading.isVisible = false
                        toast("Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleNextClick() {
        val currentGuid = MmkvManager.getSelectServer()
        val servers = mainViewModel.serversCache
        if (servers.isEmpty()) return

        val currentIndex = servers.indexOfFirst { it.guid == currentGuid }
        var nextIndex = (currentIndex + 1) % servers.size

        var found = false
        val startNextIndex = nextIndex

        do {
            val server = servers[nextIndex]
            val aff = MmkvManager.decodeServerAffiliationInfo(server.guid)
            if (aff != null && aff.testDelayMillis > 0) {
                MmkvManager.setSelectServer(server.guid)
                found = true
                break
            }
            nextIndex = (nextIndex + 1) % servers.size
        } while (nextIndex != startNextIndex)

        if (found) {
            restartV2Ray()
        } else {
            toast("No other responsive servers")
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                CoreServiceManager.startVService(this)
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            CoreServiceManager.startVService(this)
        }
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    // Dummy methods to satisfy legacy fragments during compilation
    fun refreshGroupTabTitles(refreshAll: Boolean = false) {}
    fun importConfigViaSub(): Boolean = true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
