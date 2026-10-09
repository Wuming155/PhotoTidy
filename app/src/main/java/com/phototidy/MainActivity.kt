package com.phototidy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phototidy.ui.PhotoTidyRoot
import com.phototidy.ui.theme.LocalPhotoTidyThemeState
import com.phototidy.ui.theme.PhotoTidyTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前：先装上启动画面，避免白屏闪一下
        installSplashScreen()
        // targetSdk 36 下 edge-to-edge 无法退出，这里显式调用统一两代系统的行为
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val settings by Graph.settings.state.collectAsStateWithLifecycle()

            PhotoTidyTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                pureBlack = settings.pureBlack,
            ) {
                SystemBarAppearance()
                PhotoTidyRoot(settingsStore = Graph.settings)
            }
        }
    }
}

/**
 * 同步系统栏图标的明暗。
 *
 * edge-to-edge 之下状态栏和导航栏都是透明的，压在照片上，
 * 如果不跟随主题切换图标颜色，浅色主题下会出现「白底白字」这种致命可读性问题。
 */
@Composable
private fun SystemBarAppearance() {
    val view = LocalView.current
    val dark = LocalPhotoTidyThemeState.current.isDark

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
