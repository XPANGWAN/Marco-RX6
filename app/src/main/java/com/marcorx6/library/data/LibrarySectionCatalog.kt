package com.marcorx6.library.data

/** Blogger label hierarchy used by the library's nested section browser. */
internal object LibrarySectionCatalog {
    data class Section(val id: String, val title: String, val aliases: List<String>)

    data class Platform(val id: String, val sections: List<Section>)

    private fun section(id: String, title: String, vararg aliases: String) =
        Section(id, title, (listOf(title) + aliases).distinct())

    val platforms = listOf(
        Platform("PS5", listOf(
            section("games", "الألعاب", "العاب", "Games", "Game"),
            section("arabic", "الألعاب العربية", "العاب عربية", "Arabic Games", "PS5 Arabic Games"),
            section("apps", "التطبيقات والأدوات", "التطبيقات والادوات", "Apps & Tools", "Applications", "Tools"),
            section("emulators", "محاكيات", "Emulators")
        )),
        Platform("PS4", listOf(
            section("games", "الألعاب", "العاب", "Games", "Game"),
            section("arabic", "الألعاب العربية", "العاب عربية", "Arabic Games", "PS4 Arabic Games"),
            section("dlc", "DLC", "Add-ons", "Downloadable Content"),
            section("avatars", "صور رمزية", "افتارات", "Avatars", "Profile Pictures"),
            section("ps2", "PS2 on PS4", "PS2 PS4", "PS2-PS4"),
            section("ps1", "PS1 on PS4", "PS1 PS4", "PS1-PS4"),
            section("themes", "السمات", "ثيمات", "Themes"),
            section("apps", "التطبيقات والأدوات", "التطبيقات والادوات", "Apps & Tools", "Applications", "Tools"),
            section("emulators", "محاكيات", "Emulators")
        )),
        Platform("PS3", listOf(
            section("games", "الألعاب", "العاب", "Games", "Game"),
            section("arabic", "الألعاب العربية", "العاب عربية", "Arabic Games", "PS3 Arabic Games", "PS3 Arabic"),
            section("dlc", "DLC", "Add-ons", "Downloadable Content"),
            section("avatars", "صور رمزية", "افتارات", "Avatars", "Profile Pictures"),
            section("ps2", "PS2 on PS3", "PS2 PS3", "PS2-PS3"),
            section("ps1", "PS1 on PS3", "PS1 PS3", "PS1-PS3"),
            section("themes", "السمات", "ثيمات", "Themes"),
            section("apps", "التطبيقات والأدوات", "التطبيقات والادوات", "Apps & Tools", "Applications", "Tools"),
            section("emulators", "محاكيات", "Emulators")
        ))
    )

    fun normalizeLabel(value: String): String = value
        .trim()
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\s_\\-]+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun matchesPlatform(game: Game, platformId: String): Boolean {
        val canonical = GameLibraryManager.normalizePlatform(game.platform)
        if (canonical.equals(platformId, ignoreCase = true)) return true
        if (platformId == "PS3" && canonical in setOf("PS3_ARABIC", "PS2_PORT_PS3")) return true
        if (platformId == "PS4" && canonical == "PS2_PORT_PS4") return true
        val labels = game.bloggerLabels.map(::normalizeLabel).toSet()
        return labels.contains(normalizeLabel(platformId))
    }

    fun matchesSection(game: Game, platformId: String, sectionId: String): Boolean {
        val platform = platforms.firstOrNull { it.id == platformId } ?: return false
        val section = platform.sections.firstOrNull { it.id == sectionId } ?: return false
        if (!matchesPlatform(game, platformId)) return false
        val labels = game.bloggerLabels.map(::normalizeLabel).toSet()
        return section.aliases.any { normalizeLabel(it) in labels }
    }

    fun sectionKey(platformId: String, sectionId: String): String =
        "SECTION:$platformId:$sectionId"

    fun parseSectionKey(value: String): Pair<String, String>? {
        if (!value.startsWith("SECTION:")) return null
        val parts = value.split(':')
        return if (parts.size == 3) parts[1] to parts[2] else null
    }
}
