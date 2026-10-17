package com.eyue.reader.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Appearance only: applying a theme never changes navigation, speech, chapter rules or global theme mode. */
data class ReaderAppearance(
    val fontSize: Float = 19f,
    val lineHeight: Float = 1.85f,
    val paragraphSpacing: Float = .8f,
    val letterSpacing: Float = 0f,
    val fontName: String = "跟随系统",
    val fontPath: String? = null,
    val bold: Boolean = false,
    val background: ReaderBackground = ReaderBackground.PAPER,
    val backgroundImagePath: String? = null,
    val brightness: Float = -1f,
    val showTime: Boolean = true,
    val showBattery: Boolean = true,
    val showChapter: Boolean = true
) {
    fun mapPaths(path: (String?) -> String?): ReaderAppearance = copy(fontPath = path(fontPath),
        backgroundImagePath = path(backgroundImagePath))

    internal fun toJson(): JSONObject = JSONObject()
        .put("fontSize", fontSize.toDouble()).put("lineHeight", lineHeight.toDouble())
        .put("paragraphSpacing", paragraphSpacing.toDouble()).put("letterSpacing", letterSpacing.toDouble())
        .put("fontName", fontName).put("fontPath", fontPath).put("bold", bold)
        .put("background", background.name).put("backgroundImagePath", backgroundImagePath)
        .put("brightness", brightness.toDouble()).put("showTime", showTime)
        .put("showBattery", showBattery).put("showChapter", showChapter)

    companion object {
        internal fun fromJson(json: JSONObject): ReaderAppearance {
            val defaults = ReaderAppearance()
            fun number(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>): Float =
                json.optDouble(key, fallback.toDouble()).toFloat().let { if (it.isFinite()) it.coerceIn(range) else fallback }
            fun path(key: String): String? = if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }
            return ReaderAppearance(
                fontSize = number("fontSize", defaults.fontSize, 12f..36f),
                lineHeight = number("lineHeight", defaults.lineHeight, 1.2f..2.4f),
                paragraphSpacing = number("paragraphSpacing", defaults.paragraphSpacing, 0f..2f),
                letterSpacing = number("letterSpacing", defaults.letterSpacing, -.2f..3f),
                fontName = json.optString("fontName", defaults.fontName), fontPath = path("fontPath"),
                bold = json.optBoolean("bold", defaults.bold),
                background = runCatching { ReaderBackground.valueOf(json.optString("background")) }.getOrDefault(defaults.background),
                backgroundImagePath = path("backgroundImagePath"),
                brightness = number("brightness", defaults.brightness, -1f..1f),
                showTime = json.optBoolean("showTime", defaults.showTime),
                showBattery = json.optBoolean("showBattery", defaults.showBattery),
                showChapter = json.optBoolean("showChapter", defaults.showChapter))
        }
    }
}

data class ReaderThemeProfile(val id: String = UUID.randomUUID().toString(), val name: String, val appearance: ReaderAppearance) {
    fun mapPaths(path: (String?) -> String?): ReaderThemeProfile = copy(appearance = appearance.mapPaths(path))
    internal fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("appearance", appearance.toJson())
}

internal fun readReaderThemeProfiles(array: JSONArray?): List<ReaderThemeProfile> =
    (0 until (array?.length() ?: 0)).mapNotNull { index ->
        val json = array?.optJSONObject(index) ?: return@mapNotNull null
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = json.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val appearance = json.optJSONObject("appearance") ?: return@mapNotNull null
        ReaderThemeProfile(id, name, ReaderAppearance.fromJson(appearance))
    }.distinctBy { it.id }

fun ReaderSettings.appearanceSnapshot(): ReaderAppearance = ReaderAppearance(fontSize, lineHeight, paragraphSpacing,
    letterSpacing, fontName, fontPath, bold, background, backgroundImagePath, brightness, showTime, showBattery, showChapter)

fun ReaderSettings.applyAppearanceProfile(profile: ReaderThemeProfile): ReaderSettings = with(profile.appearance) {
    this@applyAppearanceProfile.copy(fontSize = fontSize, lineHeight = lineHeight, paragraphSpacing = paragraphSpacing,
        letterSpacing = letterSpacing, fontName = fontName, fontPath = fontPath, bold = bold, background = background,
        backgroundImagePath = backgroundImagePath, brightness = brightness, showTime = showTime,
        showBattery = showBattery, showChapter = showChapter, activeAppearanceProfileId = profile.id)
}

fun ReaderSettings.saveAppearanceProfile(): ReaderSettings {
    val used = appearanceProfiles.map { it.name }.toSet()
    val number = generateSequence(1) { it + 1 }.first { "配置$it" !in used }
    val profile = ReaderThemeProfile(name = "配置$number", appearance = appearanceSnapshot())
    return copy(appearanceProfiles = appearanceProfiles + profile, activeAppearanceProfileId = profile.id)
}
