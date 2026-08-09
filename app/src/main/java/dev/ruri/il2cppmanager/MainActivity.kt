package dev.ruri.il2cppmanager

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ruri.il2cppmanager.preferences.ThemePreferenceStore
import dev.ruri.il2cppmanager.presentation.ManagerViewModel
import dev.ruri.il2cppmanager.ui.ManagerScreen
import java.io.IOException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val managerViewModel by viewModels<ManagerViewModel>()
    private val themePreferenceStore by lazy { ThemePreferenceStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateSystemBars(darkTheme = true)
        setContent {
            val darkThemePreference by themePreferenceStore.darkTheme.collectAsStateWithLifecycle(
                initialValue = null,
            )
            val darkTheme = darkThemePreference ?: return@setContent
            val state by managerViewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(darkTheme) {
                updateSystemBars(darkTheme)
            }
            ManagerScreen(
                state = state,
                onAction = managerViewModel::onAction,
                darkTheme = darkTheme,
                onDarkThemeChanged = ::setDarkTheme,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        managerViewModel.onHostStarted()
    }

    override fun onStop() {
        managerViewModel.onHostStopped()
        super.onStop()
    }

    private fun setDarkTheme(enabled: Boolean) {
        lifecycleScope.launch {
            try {
                themePreferenceStore.setDarkTheme(enabled)
            } catch (_: IOException) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.theme_preference_save_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun updateSystemBars(darkTheme: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = if (darkTheme) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            },
        )
    }
}
