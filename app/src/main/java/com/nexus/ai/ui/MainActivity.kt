package com.nexus.ai.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.nexus.ai.R
import com.nexus.ai.core.Assistant
import com.nexus.ai.core.AvatarState
import com.nexus.ai.service.WakeService
import com.nexus.ai.ui.avatar.BootView
import com.nexus.ai.ui.screens.ChatFragment
import com.nexus.ai.ui.screens.HomeFragment
import com.nexus.ai.ui.screens.ModesFragment
import com.nexus.ai.ui.screens.SettingsFragment
import com.nexus.ai.ui.screens.ToolsFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var confirmDialog: AlertDialog? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "Permission mil gayi. Ab apna command dobara bolo." else "Permission nahi mili, ye feature kaam nahi karega.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val boot = findViewById<BootView>(R.id.boot)
        if (savedInstanceState != null) boot.visibility = android.view.View.GONE

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            val f: Fragment = when (item.itemId) {
                R.id.nav_chat -> ChatFragment()
                R.id.nav_modes -> ModesFragment()
                R.id.nav_tools -> ToolsFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> HomeFragment()
            }
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.container, f)
                .commit()
            true
        }
        if (savedInstanceState == null) nav.selectedItemId = R.id.nav_home

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Assistant.notices.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() } }
                launch { Assistant.permissionRequests.collect { askPermission(it) } }
                launch { Assistant.pending.collect { showPending(it) } }
            }
        }

        // Restart the wake-word service if the user turned it on earlier (the app is visible, so Android allows this).
        if (Assistant.prefs.wakeWord && Perm.hasMic(this) && !WakeService.running) {
            try { WakeService.start(this) } catch (_: Exception) {}
        }
    }

    private fun askPermission(perm: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission chahiye")
            .setMessage(Perm.explain(perm))
            .setPositiveButton("Allow") { _, _ -> permLauncher.launch(perm) }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Shows a Yes/No box for sensitive actions. The same question is also asked aloud, so saying "haan"/"nahi" works too. */
    private fun showPending(p: com.nexus.ai.core.Pending?) {
        confirmDialog?.dismiss()
        confirmDialog = null
        if (p == null) return
        confirmDialog = AlertDialog.Builder(this)
            .setTitle("Confirm karo")
            .setMessage(p.prompt)
            .setPositiveButton("Yes") { _, _ -> Assistant.confirm(true) }
            .setNegativeButton("No") { _, _ -> Assistant.confirm(false) }
            .setOnCancelListener { Assistant.confirm(false) }
            .show()
    }

    override fun onStop() {
        super.onStop()
        // The microphone is never left open in the background (except the wake-word service you turned on yourself).
        if (Assistant.state.value == AvatarState.LISTENING && !WakeService.running) Assistant.stopAll()
    }
}
