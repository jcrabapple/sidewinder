package net.snakepit.sidewinder.ui.profile

/**
 * Image replacements and descriptions relative to the values loaded into the editor.
 * Empty descriptions are meaningful: they clear alt text that was previously set.
 */
internal data class ProfileImageEdits(
    val initialAvatarDescription: String = "",
    val initialHeaderDescription: String = "",
    val avatarDescription: String = "",
    val headerDescription: String = "",
    val avatarSelected: Boolean = false,
    val headerSelected: Boolean = false,
) {
    val dirty: Boolean
        get() = avatarSelected || headerSelected ||
            avatarDescription != initialAvatarDescription ||
            headerDescription != initialHeaderDescription

    /** Only changed descriptions, plus descriptions accompanying replacement images, are sent. */
    fun descriptionFields(): Map<String, String> = buildMap {
        if (avatarSelected || avatarDescription != initialAvatarDescription) {
            put("avatar_description", avatarDescription)
        }
        if (headerSelected || headerDescription != initialHeaderDescription) {
            put("header_description", headerDescription)
        }
    }
}
