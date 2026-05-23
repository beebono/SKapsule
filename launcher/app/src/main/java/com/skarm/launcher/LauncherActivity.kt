package com.skarm.launcher

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
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
        binding.btnPlaySteam.setOnClickListener { onPlaySteam() }

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

    /**
     * Steam path. frenchpress persists a Steam refresh token after the first
     * successful login, so we only prompt for username/password when no creds file
     * exists yet; subsequent launches resume silently from the token. The collected
     * credentials are passed straight through to the game JVM (env vars set in
     * sklauncher.c) and never stored in plaintext by the launcher.
     */
    private fun onPlaySteam() {
        if (FrenchpressInstaller.credFile(this).exists()) {
            launchGame(LoginMode.Steam)
        } else {
            promptSteamLogin()
        }
    }

    private fun promptSteamLogin() {
        val pad = (resources.displayMetrics.density * 20).toInt()
        val userField = EditText(this).apply {
            hint = getString(R.string.steam_username)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val passField = EditText(this).apply {
            hint = getString(R.string.steam_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(userField)
            addView(passField)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.steam_login_title)
            .setMessage(R.string.steam_login_message)
            .setView(layout)
            .setPositiveButton(R.string.steam_login_ok) { _, _ ->
                val user = userField.text.toString().trim()
                val pass = passField.text.toString()
                // Empty username => web account; frenchpress treats it as such.
                launchGame(LoginMode.Steam, user, pass)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchGame(mode: LoginMode, steamUser: String = "", steamPass: String = "") {
        startActivity(Intent(this, GameActivity::class.java).apply {
            putExtra(EXTRA_LOGIN_MODE, mode.name)
            if (steamUser.isNotEmpty()) {
                putExtra(EXTRA_STEAM_USER, steamUser)
                putExtra(EXTRA_STEAM_PASS, steamPass)
            }
        })
    }

    enum class LoginMode { Web, Steam }

    companion object {
        private const val TAG = "LauncherActivity"
        const val EXTRA_LOGIN_MODE = "com.skarm.launcher.LOGIN_MODE"
        const val EXTRA_STEAM_USER = "com.skarm.launcher.STEAM_USER"
        const val EXTRA_STEAM_PASS = "com.skarm.launcher.STEAM_PASS"
    }
}
