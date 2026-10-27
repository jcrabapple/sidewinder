package net.snakepit.sidewinder.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    val dynamicColorsActive = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SettingsSectionHeader("Appearance")

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

        ThemePicker(
            selectedId = settings.themeId,
            enabled = !dynamicColorsActive,
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
            subtitle = when {
                dynamicColorsActive ->
                    "Dynamic colors are on, so pure black cannot change the background. " +
                        "Turn them off to use pure black."
                settings.theme == ThemePref.LIGHT ->
                    "Black backgrounds in dark mode. Switch the mode to System or Dark to use it."
                else -> "Black backgrounds in dark mode. Saves battery on OLED screens."
            },
            checked = settings.pureBlack,
            label = "Pure black toggle",
            onCheckedChange = onPureBlack,
            enabled = !dynamicColorsActive && settings.theme != ThemePref.LIGHT,
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

/** Compact current-theme preview with a scrollable, immediately applied chooser. */
@Composable
private fun ThemePicker(
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var showChooser by rememberSaveable { mutableStateOf(false) }
    val selectedTheme = AppThemes.byId(selectedId)
    val shape = RoundedCornerShape(10.dp)

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = { showChooser = true },
                )
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(12.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Choose theme, currently ${selectedTheme.name}"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selectedTheme.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VariantPanel(
                        palette = selectedTheme.light,
                        dark = false,
                        modifier = Modifier.weight(1f),
                    )
                    VariantPanel(
                        palette = selectedTheme.dark,
                        dark = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
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

    if (showChooser) {
        AlertDialog(
            onDismissRequest = { showChooser = false },
            title = { Text("Choose theme") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(AppThemes.all, key = { it.id }) { theme ->
                        ThemeCard(
                            theme = theme,
                            selected = theme.id == selectedId,
                            enabled = true,
                            onSelect = { onSelect(theme.id) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChooser = false }) {
                    Text("Done")
                }
            },
        )
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
