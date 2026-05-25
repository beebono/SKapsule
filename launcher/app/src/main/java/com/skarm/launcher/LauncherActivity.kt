package com.skarm.launcher

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.skarm.launcher.databinding.ActivityLauncherBinding
import java.io.File
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

    // Captured dump awaiting the SAF picker's chosen destination (the save flow is
    // async: launch picker -> onResult writes to the URI). Held across that hop.
    private var pendingSave: File? = null

    // CreateDocument picker (no storage permission needed; DocumentsUI does the
    // write). Result is the user-chosen content:// URI, or null if cancelled.
    private val saveLog = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val src = pendingSave.also { pendingSave = null }
        if (uri == null || src == null) return@registerForActivityResult
        val ok = LogExporter.copyToUri(this, src, uri)
        Toast.makeText(
            this, if (ok) R.string.save_logs_saved else R.string.save_logs_failed,
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPlayWeb.setOnClickListener { launchGame(LoginMode.Web) }
        binding.btnPlaySteam.setOnClickListener { onPlaySteam() }
        binding.btnShareLogs.setOnClickListener { LogExporter.captureAndShare(this) }
        binding.btnSaveLogs.setOnClickListener { onSaveLogs() }

        // If the previous session crashed, the handler auto-saved a dump. Offer to
        // share it right away (once per launch); the button stays available too.
        LogExporter.latestCrash(this)?.let { crash ->
            AlertDialog.Builder(this)
                .setMessage(R.string.share_logs_crash_found)
                .setPositiveButton(R.string.share_logs) { _, _ -> LogExporter.share(this, crash) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        ensureRuntime()
    }

    /**
     * Gate the Share Logs button on there being something worth sending: a launch
     * was attempted this install, or a crash dump is on disk. Re-checked on resume
     * because the launch marker is written by the :game process while we're paused.
     */
    override fun onResume() {
        super.onResume()
        val hasLogs = LogExporter.wasLaunchAttempted(this) || LogExporter.latestCrash(this) != null
        binding.btnShareLogs.isEnabled = hasLogs
        binding.btnSaveLogs.isEnabled = hasLogs
    }

    /** Captures a dump, then opens the SAF picker (suggesting its filename) to save it. */
    private fun onSaveLogs() {
        val file = LogExporter.capture(this)
        if (file == null) {
            Toast.makeText(this, R.string.share_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        pendingSave = file
        saveLog.launch(file.name)
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
