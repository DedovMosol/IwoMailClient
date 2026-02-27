package com.dedovmosol.iwomail.ui.utils

import androidx.compose.ui.graphics.Color

private val avatarColors = listOf(
    Color(0xFFE53935), Color(0xFFD81B60), Color(0xFFC2185B),
    Color(0xFF8E24AA), Color(0xFF7B1FA2), Color(0xFF5E35B1), Color(0xFF512DA8),
    Color(0xFF3949AB), Color(0xFF303F9F), Color(0xFF1E88E5), Color(0xFF1976D2),
    Color(0xFF039BE5), Color(0xFF0288D1), Color(0xFF00ACC1), Color(0xFF0097A7),
    Color(0xFF00897B), Color(0xFF00796B), Color(0xFF43A047), Color(0xFF388E3C),
    Color(0xFF7CB342), Color(0xFF689F38), Color(0xFFC0CA33), Color(0xFFAFB42B),
    Color(0xFFFDD835), Color(0xFFFBC02D), Color(0xFFFFB300), Color(0xFFFFA000),
    Color(0xFFFB8C00), Color(0xFFF57C00), Color(0xFFF4511E), Color(0xFFE64A19),
    Color(0xFF6D4C41), Color(0xFF5D4037), Color(0xFF546E7A), Color(0xFF455A64)
)

fun getAvatarColor(name: String): Color {
    if (name.isBlank()) return avatarColors[0]
    val hash = name.lowercase().hashCode()
    return avatarColors[(hash and 0x7FFFFFFF) % avatarColors.size]
}
