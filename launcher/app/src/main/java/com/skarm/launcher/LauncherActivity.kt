package com.skarm.launcher

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.skarm.launcher.databinding.ActivityLauncherBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Launcher home screen. Two paths to Spiral Knights:
 *   - Web account login
 *   - Steam-linked account login
 *
 * Also responsible for first-launch JRE 25 extraction. Buttons are disabled
 * until the runtime is on disk; subsequent launches skip the unpack entirely.
 */
class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPlayWeb.setOnClickListener { launchGame(LoginMode.Web) }
        binding.btnPlaySteam.setOnClickListener { launchGame(LoginMode.Steam) }

        ensureRuntime()
    }

    private fun ensureRuntime() {
        val jreReady = JreInstaller.isInstalled(this)
        val lwjglReady = LwjglInstaller.isInstalled(this)
        val skBootstrapped = SkInstaller.isBootstrapped(this)
        if (jreReady && lwjglReady && skBootstrapped) {
            Log.i(TAG, "Runtime already installed (jre=$jreReady lwjgl=$lwjglReady sk=$skBootstrapped)")
            setButtonsEnabled(true)
            return
        }

        setButtonsEnabled(false)
        binding.setupGroup.visibility = View.VISIBLE
        binding.setupStatus.text = getString(R.string.setup_starting)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val report: (String) -> Unit = { msg ->
                        runOnUiThread { binding.setupStatus.text = msg }
                    }
                    if (!jreReady)        JreInstaller.install(this@LauncherActivity, report)
                    if (!lwjglReady)      LwjglInstaller.install(this@LauncherActivity, report)
                    if (!skBootstrapped)  SkInstaller.bootstrap(this@LauncherActivity, report)
                }
                binding.setupGroup.visibility = View.GONE
                setButtonsEnabled(true)
            } catch (t: Throwable) {
                Log.e(TAG, "Runtime install failed", t)
                binding.setupStatus.text = "Runtime setup failed: ${t.message}"
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnPlayWeb.isEnabled = enabled
        binding.btnPlaySteam.isEnabled = enabled
    }

    private fun launchGame(mode: LoginMode) {
        startActivity(Intent(this, GameActivity::class.java).apply {
            putExtra(EXTRA_LOGIN_MODE, mode.name)
        })
    }

    enum class LoginMode { Web, Steam }

    companion object {
        private const val TAG = "LauncherActivity"
        const val EXTRA_LOGIN_MODE = "com.skarm.launcher.LOGIN_MODE"
    }
}
