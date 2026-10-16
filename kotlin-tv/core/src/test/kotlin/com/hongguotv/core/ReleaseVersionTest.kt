package com.hongguotv.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the release version against the mistake that shipped 0.9.0 twice.
 *
 * The in-app updater only accepts `versionCode > installed`, so if the default versionCode in the
 * app module is not raised for a release, the new build is invisible to every already-installed
 * device and can only be replaced by hand. Nothing else in the build catches that: the APK compiles,
 * signs, installs and plays perfectly -- it simply never arrives.
 *
 * The test reads the real build script rather than a copy, so it fails on the actual release path.
 */
class ReleaseVersionTest {
    private fun appBuildScript(): File {
        // Gradle runs tests with the module directory as the working directory.
        val candidates = listOf(
            File("../app/build.gradle.kts"),
            File("kotlin-tv/app/build.gradle.kts"),
            File("app/build.gradle.kts")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 app/build.gradle.kts，查找过：${candidates.map { it.absolutePath }}")
    }

    private fun defaultVersion(property: String): String {
        val text = appBuildScript().readText()
        // Match the fallback after `?:` on the versionCode / versionName lines.
        val match = Regex("""$property\s*=\s*System\.getenv\("[A-Z_]+"\)\?\.toInt\(\)\s*\?:\s*(\d+)""").find(text)
            ?: Regex("""$property\s*=\s*System\.getenv\("[A-Z_]+"\)\s*\?:\s*"([^"]+)"""").find(text)
            ?: error("在 app/build.gradle.kts 里找不到 $property 的默认值")
        return match.groupValues[1]
    }

    /** The highest versionCode already published. Raise this only when a release is actually cut. */
    private val lastReleasedVersionCode = 9L

    @Test fun defaultVersionCodeIsHigherThanTheLastRelease() {
        val current = defaultVersion("versionCode").toLong()
        assertTrue(
            "默认 versionCode=$current 必须大于上一个已发布版本 $lastReleasedVersionCode，" +
                "否则已安装设备的应用内更新看不到这个包（更新器只接受 versionCode > installed）",
            current > lastReleasedVersionCode
        )
    }

    @Test fun versionNameIsAWellFormedReleaseName() {
        val name = defaultVersion("versionName")
        assertTrue(
            "versionName=\"$name\" 不符合更新元数据允许的格式（须以数字开头，仅含数字/字母/点/加号/减号）",
            name.matches(Regex("[0-9][0-9A-Za-z.+-]{0,63}"))
        )
    }
}
