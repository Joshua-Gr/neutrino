package io.github.joshuagr.neutrino.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.github.joshuagr.neutrino.R

/** Inter, one variable font file, instanced per weight. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val Inter = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.inter, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

private val InterTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Inter),
        displayMedium = displayMedium.copy(fontFamily = Inter),
        displaySmall = displaySmall.copy(fontFamily = Inter),
        headlineLarge = headlineLarge.copy(fontFamily = Inter),
        headlineMedium = headlineMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = Inter),
        bodyMedium = bodyMedium.copy(fontFamily = Inter),
        bodySmall = bodySmall.copy(fontFamily = Inter),
        labelLarge = labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
        labelSmall = labelSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium),
    )
}

// Every role is set: Material 3 fills unset surface-container roles from its own baseline.
private val Dark = darkColorScheme(
    primary = Color(0xFF8A6EFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF372580),
    onPrimaryContainer = Color(0xFFE2DBFF),
    secondary = Color(0xFFA7A4B5),
    onSecondary = Color(0xFF16141C),
    background = Color(0xFF16141C),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF16141C),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF292733),
    onSurfaceVariant = Color(0xFFA7A4B5),
    surfaceDim = Color(0xFF110F16),
    surfaceBright = Color(0xFF34313F),
    surfaceContainerLowest = Color(0xFF110F16),
    surfaceContainerLow = Color(0xFF1B1923),
    surfaceContainer = Color(0xFF201E29),
    surfaceContainerHigh = Color(0xFF292733),
    surfaceContainerHighest = Color(0xFF34313F),
    outline = Color(0xFF4A4658),
    outlineVariant = Color(0xFF34313F),
    error = Color(0xFFF5385A),
    onError = Color(0xFFFFFFFF),
)

private val Light = lightColorScheme(
    primary = Color(0xFF6D4AFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE8FF),
    onPrimaryContainer = Color(0xFF372580),
    secondary = Color(0xFF706D6B),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0C0C14),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0C0C14),
    surfaceVariant = Color(0xFFF5F4F2),
    onSurfaceVariant = Color(0xFF706D6B),
    surfaceDim = Color(0xFFEAE7E4),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F8F7),
    surfaceContainer = Color(0xFFF5F4F2),
    surfaceContainerHigh = Color(0xFFEFEDEB),
    surfaceContainerHighest = Color(0xFFEAE7E4),
    outline = Color(0xFFD1CFCD),
    outlineVariant = Color(0xFFEAE7E4),
    error = Color(0xFFDC3251),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun NeutrinoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = InterTypography,
        content = content,
    )
}
