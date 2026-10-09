// 顶层构建脚本。所有插件在这里声明版本，由子模块按需 apply。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
