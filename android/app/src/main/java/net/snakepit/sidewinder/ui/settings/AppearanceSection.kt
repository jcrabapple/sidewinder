package net.snakepit.sidewinder.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.snakepit.sidewinder.core.settings.AppSettings
import net.snakepit.sidewinder.core.settings.ThemePref
import net.snakepit.sidewinder.ui.theme.AppTheme
import net.snakepit.sidewinder.ui.theme.AppThemes
import net.snakepit.sidewinder.ui.theme.ThemePalette

/**
 * The Appearance section: mode, bundled theme picker, dynamic colors,
 * pure black and text size. Lives first in the settings list.
 */

private val modeOptions = listOf(
    ThemePref.SYSTEM to "System",
    ThemePref.LIGHT to "Light",
    ThemePref.DARK to "Dark",
)

@Composable
internal fun AppearanceSection(
    settings: AppSettings,
    onMode: (ThemePref) -> Unit,
    onThemeId: (String) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onPureBlack: (Boolean) -> Unit,
    onTextScale: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = "Appearance",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        Text(
            text = "Mode",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modeOptions.forEachIndexed { index, (pref, label) ->
                SegmentedButton(
                    selected = when (pref) {
                        ThemePref.SYSTEM -> settings.theme == ThemePref.SYSTEM
                        ThemePref.LIGHT -> settings.theme == ThemePref.LIGHT
                        else -> settings.theme == ThemePref.DARK
                    },
                    onClick = { onMode(pref) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modeOptions.size),
                    // AMOLED (a legacy stored value) reads as dark mode here.
                    label = { Text(label) },
                )
            }
        }

        ThemeGrid(
            selectedId = settings.themeId,
            enabled = !settings.dynamicColor,
            onSelect = onThemeId,
        )

        ToggleRow(
            title = "Dynamic colors",
            subtitle = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                "Needs Android 12 or newer."
            } else {
                "Use colors from your wallpaper. Android 12 and newer."
            },
            checked = settings.dynamicColor,
            label = "Dynamic colors toggle",
            onCheckedChange = onDynamicColor,
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        )

        ToggleRow(
            title = "Pure black",
            subtitle = if (settings.theme == ThemePref.LIGHT) {
                "Black backgrounds in dark mode. Switch the mode to System or Dark to use it."
            } else {
                "Black backgrounds in dark mode. Saves battery on OLED screens."
            },
            checked = settings.pureBlack,
            label = "Pure black toggle",
            onCheckedChange = onPureBlack,
            enabled = settings.theme != ThemePref.LIGHT,
        )

        DropdownSettingRow(
            title = "Text size",
            currentLabel = textScaleLabel(settings.textScale),
            contentDescription = "Choose the interface text size",
            options = textScaleOptions,
            onSelect = onTextScale,
        )
    }
}

/** Theme cards in rows of two (a grid would not scroll inside this list). */
@Composable
private fun ThemeGrid(
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.5f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppThemes.all.chunked(2).forEach { rowThemes ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowThemes.forEach { theme ->
                        ThemeCard(
                            theme = theme,
                            selected = theme.id == selectedId,
                            enabled = enabled,
                            onSelect = { onSelect(theme.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowThemes.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        if (!enabled) {
            Text(
                text = "Dynamic colors are on, so your wallpaper sets the colors. " +
                    "Turn them off to pick a theme.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** One selectable theme card: name plus a preview of both variants. */
@Composable
private fun ThemeCard(
    theme: AppTheme,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    val border = if (selected) {
        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .border(border, shape)
            .padding(8.dp)
            .semantics { contentDescription = "${theme.name} theme" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = theme.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                VariantPanel(palette = theme.light, dark = false, modifier = Modifier.weight(1f))
                VariantPanel(palette = theme.dark, dark = true, modifier = Modifier.weight(1f))
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp).size(18.dp),
            )
        }
    }
}

/** Small two-tone preview: surface strip, accent dot, text line, action dots. */
@Composable
private fun VariantPanel(palette: ThemePalette, dark: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = 40.dp)
            .background(palette.bg, RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(palette.surface, RoundedCornerShape(3.dp)),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(palette.accent, CircleShape),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Aa",
                color = palette.fg,
                fontSize = 10.sp,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(palette.favourite, CircleShape),
            )
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(palette.bookmark, CircleShape),
            )
        }
    }
}
