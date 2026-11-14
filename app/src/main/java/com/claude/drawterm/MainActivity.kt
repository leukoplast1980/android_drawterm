package com.claude.drawterm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.claude.drawterm.data.HomeProvider
import com.claude.drawterm.data.Profile
import com.claude.drawterm.data.ProfileStore
import com.claude.drawterm.data.SecretStore
import com.claude.drawterm.data.Storage
import com.claude.drawterm.session.SessionActivity
import com.claude.drawterm.ui.AppActions
import com.claude.drawterm.ui.DrawtermApp
import com.claude.drawterm.ui.DrawtermTheme

/** The server list. Sessions run in their own process (SessionActivity). */
class MainActivity : ComponentActivity(), AppActions {
    private lateinit var store: ProfileStore
    private lateinit var secrets: SecretStore

    private var profiles by mutableStateOf<List<Profile>>(emptyList())
    private var problems by mutableStateOf<Map<String, String>>(emptyMap()) // why sessions ended
    private var phoneAccess by mutableStateOf(false)
    private var launched: String? = null
    private var pending: Profile? = null

    private val sessionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onSessionResult)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            phoneAccess = Storage.hasPhoneAccess(this)
            pending?.let(::launchSession)
            pending = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        store = ProfileStore(this)
        secrets = SecretStore(this)
        savedInstanceState?.run {
            launched = getString(STATE_LAUNCHED)
            val ids = getStringArray(STATE_PROBLEM_IDS).orEmpty()
            val texts = getStringArray(STATE_PROBLEM_TEXTS).orEmpty()
            problems = ids.zip(texts).toMap()
        }
        setContent {
            DrawtermTheme { DrawtermApp(profiles, problems, phoneAccess, this@MainActivity) }
        }
    }

    override fun onResume() {
        super.onResume()
        reload() // a session may have changed settings
        phoneAccess = Storage.hasPhoneAccess(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_LAUNCHED, launched)
        outState.putStringArray(STATE_PROBLEM_IDS, problems.keys.toTypedArray())
        outState.putStringArray(STATE_PROBLEM_TEXTS, problems.values.toTypedArray())
    }

    private fun reload() {
        profiles = store.load().sortedWith(compareByDescending<Profile> { it.lastUsed }.thenBy { it.title.lowercase() })
    }

    override fun connect(p: Profile) {
        store.update(p.id) { it.copy(lastUsed = System.currentTimeMillis()) }
        problems = problems - p.id
        reload()
        // From Android 17 on, apps targeting it must ask before reaching the LAN.
        if (Build.VERSION.SDK_INT >= 37 && applicationInfo.targetSdkVersion >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = p
            permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            return
        }
        launchSession(p)
    }

    override fun connectWithoutAan(p: Profile) {
        store.update(p.id) { it.copy(aan = false) }
        store.get(p.id)?.let(::connect)
    }

    override fun dismissProblem(p: Profile) {
        problems = problems - p.id
    }

    override fun save(p: Profile, password: String?) {
        store.put(p)
        when {
            !p.savePassword -> secrets.remove(p.id)
            !password.isNullOrEmpty() -> secrets.put(p.id, password)
        }
        reload()
    }

    override fun delete(p: Profile) {
        store.delete(p.id)
        secrets.remove(p.id)
        problems = problems - p.id
        reload()
    }

    override fun hasSavedPassword(p: Profile) = secrets.has(p.id)

    override fun requestPhoneAccess() {
        val settings = Storage.phoneAccessSettings(this)
        if (settings == null) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            runCatching { startActivity(settings) }
                .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    /** The drawterm folder in Android's Files app. */
    override fun openFiles() {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(HomeProvider.rootUri(this), DocumentsContract.Root.MIME_TYPE_ITEM)
        runCatching { startActivity(view) }
            .onFailure { Toast.makeText(this, R.string.files_unavailable, Toast.LENGTH_LONG).show() }
    }

    private fun launchSession(p: Profile) {
        launched = p.id
        sessionLauncher.launch(Intent(this, SessionActivity::class.java).putExtra(SessionActivity.EXTRA_PROFILE, p.id))
    }

    private fun onSessionResult(r: ActivityResult) {
        val data = r.data
        val id = data?.getStringExtra(SessionActivity.EXTRA_PROFILE) ?: launched ?: return
        launched = null
        // no data: the session process died without saying why
        val code = data?.getIntExtra(SessionActivity.EXTRA_CODE, 0) ?: -1
        if (code != 0) {
            val message = data?.getStringExtra(SessionActivity.EXTRA_MESSAGE).orEmpty().ifBlank {
                if (data == null) getString(R.string.session_lost) else getString(R.string.session_failed, code)
            }
            problems = problems + (id to message)
        }
        reload()
    }

    private companion object {
        const val STATE_LAUNCHED = "launched"
        const val STATE_PROBLEM_IDS = "problemIds"
        const val STATE_PROBLEM_TEXTS = "problemTexts"
    }
}
