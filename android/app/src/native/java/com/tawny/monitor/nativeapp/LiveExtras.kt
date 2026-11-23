package com.tawny.monitor.nativeapp

import androidx.compose.animation.core.animateRectAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.lerp
import com.tawny.monitor.R
import kotlinx.coroutines.delay

/* The pieces of the web live screen that sit around the picture rather than
   on the control row: the Announced bell, the "Listening to you" flag, the
   zoom chip, the theme toggle, "Who's here" and the first-call tour. Each
   follows its rule in public/style.css. */

/** Marks a key the first-call tour can point at. */
internal fun Modifier.coachTarget(page: NativeLivePage, key: String) =
    onGloballyPositioned { page.targets[key] = it.boundsInRoot() }

/** Monitor: viewers are announced with a sound. A bell and a word. */
@Composable
internal fun AnnounceChip() {
    val label = stringResource(R.string.w_aria_announce)
    Row(
        Modifier.clip(R_CHIP).background(Color.White.copy(alpha = 0.08f)).border(1.dp, GlassLine, R_CHIP)
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_ctl_chime), null, tint = GlassFg, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.w_announce_chip).uppercase(), color = GlassFg, fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 0.04.em, style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Monitor: a Viewer is holding its talk key. The red flag under the rail. */
@Composable
internal fun BoxScope.TalkFlag(page: NativeLivePage) {
    val t = tokens()
    Box(
        Modifier.align(Alignment.TopCenter).padding(top = (92 + page.insets.top).dp)
            .clip(R_CHIP).background(t.live).padding(horizontal = 15.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            stringResource(R.string.w_listening_to_you).uppercase(), color = Color.White, fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.16.em, style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** The zoom level, big in the middle, while it changes and two seconds after. */
@Composable
internal fun BoxScope.ZoomChip(level: Float, at: Long) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(at) {
        if (at == 0L) return@LaunchedEffect
        shown = true
        delay(2000)
        shown = false
    }
    if (!shown) return
    Box(
        Modifier.align(Alignment.Center).clip(R_CARD).background(Glass).border(1.dp, GlassLine, R_CARD)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            if (level <= 1.005f) "1×" else "%.1f×".format(level), color = GlassFg, fontSize = 24.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 0.04.em, style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** Follow system → light → dark: a small round key above the controls. */
@Composable
internal fun BoxScope.ThemeToggle(page: NativeLivePage, pip: Boolean = false) {
    val t = tokens()
    // Above the corner picture when there is one (it is 27% of the width,
    // at most 150 dp, 3:4), not on top of it.
    val lift = if (pip) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.let { w ->
            (minOf(w * 0.27f, 150f) * 4f / 3f + 16f).dp
        }
    } else 0.dp
    val now = stringResource(
        when (page.themeMode) {
            "dark" -> R.string.w_theme_dark
            "light" -> R.string.w_theme_light
            else -> R.string.w_theme_system
        },
    )
    val change = stringResource(R.string.w_theme_change)
    Box(
        Modifier.align(Alignment.BottomEnd).padding(end = (5 + page.insets.right).dp, bottom = lift)
            .size(48.dp).clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = change, onClick = page::cycleTheme)
            .semantics { contentDescription = "$change — $now" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(t.panel.copy(alpha = 0.92f)).border(1.dp, t.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (page.themeMode) { "dark" -> "☾"; "light" -> "☀\uFE0E"; else -> "◐" },
                color = t.dim, fontSize = 16.sp, style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** "Who's here": everyone on this camera, by the name they gave and their phone. */
@Composable
internal fun WhoSheet(page: NativeLivePage, list: List<Who>?, onClose: () -> Unit) {
    val t = tokens()
    var renaming by remember { mutableStateOf(false) }
    val title = stringResource(R.string.w_who_title)
    Box(
        Modifier.fillMaxSize().background(Color(0xA80C0D12))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(t.panel)
                .border(1.dp, t.line, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                // Taps on the card stay on the card.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .semantics { paneTitle = title }
                .padding(start = 20.dp, end = 20.dp, top = 7.dp, bottom = (20 + page.insets.bottom).dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 3.dp).size(38.dp, 4.dp).clip(CircleShape).background(t.line))
            if (renaming) {
                Rename(page, t) { renaming = false }
                return@Column
            }
            Text(
                title, color = t.fg, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() },
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list.orEmpty().forEach { WhoRow(it, t) }
            }
            if (list == null) {
                Text(stringResource(R.string.w_who_old), color = t.dim, fontSize = 14.sp, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 2.dp)) {
                Wide(stringResource(R.string.w_who_rename), primary = false, t = t) { renaming = true }
                Wide(stringResource(R.string.w_who_close), primary = true, t = t, onClick = onClose)
            }
        }
    }
}

@Composable
private fun WhoRow(e: Who, t: Tokens) {
    val unnamed = e.name.isBlank()
    val role = stringResource(if (e.monitor) R.string.w_who_monitor else R.string.w_who_viewer)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(R_CTL)
            .background(if (e.you) t.raise else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(if (e.monitor) t.berry else lerp(t.panel, t.fg, 0.46f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (e.name.ifBlank { e.device }.trim().firstOrNull()?.uppercaseChar() ?: '?').toString(),
                color = if (e.monitor) t.onAccent else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                e.name.ifBlank { stringResource(R.string.w_who_noname) },
                color = if (unnamed) t.dim else t.fg, fontSize = 16.sp,
                fontWeight = if (unnamed) FontWeight.Medium else FontWeight.SemiBold,
                fontStyle = if (unnamed) FontStyle.Italic else FontStyle.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                listOf(role, e.device).filter { it.isNotBlank() }.joinToString(" · "), color = t.dim, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            )
        }
        if (e.you) {
            Text(
                stringResource(R.string.w_who_you).uppercase(), color = if (t.dark) t.berry else t.berryDeep,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.em, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** "Change my name", in place of the list. */
@Composable
private fun Rename(page: NativeLivePage, t: Tokens, done: () -> Unit) {
    var name by remember { mutableStateOf(page.myName) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = { page.rename(name); done() }
    Text(
        stringResource(R.string.myname_title), color = t.fg, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() },
    )
    BasicTextField(
        value = name,
        onValueChange = { name = it.take(24) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = t.fg, fontSize = 17.sp),
        cursorBrush = SolidColor(t.berry),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
            .semantics { contentDescription = "Your name" },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(R_CTL).background(t.raise)
                    .border(1.dp, t.berry, R_CTL).padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) { inner() }
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(top = 2.dp)) {
        Wide(stringResource(R.string.common_cancel), primary = false, t = t, onClick = done)
        Wide(stringResource(R.string.common_save), primary = true, t = t, onClick = save)
    }
}

/** The web page's `.wide` key: full width, a word in capitals. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.Wide(label: String, primary: Boolean, t: Tokens, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).heightIn(min = 52.dp).clip(R_CTL)
            .background(if (primary) t.berry else Color.Transparent)
            .border(1.dp, if (primary) t.berry else t.line, R_CTL)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(), color = if (primary) t.onAccent else t.fg, fontSize = 13.5.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium, letterSpacing = 0.1.em,
            textAlign = TextAlign.Center, maxLines = 2, style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The first-call tour: a ring cut out of a dim veil around one key, and a
 * bubble saying what it does. Tapping anywhere moves on; Skip ends it.
 */
@Composable
internal fun CoachOverlay(page: NativeLivePage, c: NativeLivePage.Coach) {
    val t = tokens()
    val (key, text) = c.steps[c.at]
    val target = page.targets[key] ?: Rect.Zero
    val pad = with(LocalDensity.current) { 6.dp.toPx() }
    val ringTarget = Rect(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
    val ring by animateRectAsState(ringTarget, if (page.motion) tween(280) else snap(), label = "ring")
    val berry = t.berry
    val radius = with(LocalDensity.current) { 17.dp.toPx() }
    val stroke = with(LocalDensity.current) { 2.dp.toPx() }
    Box(
        Modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = page::coachNext),
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
            drawRect(Color(0x9E08070A))
            drawRoundRect(Color.Black, ring.topLeft, ring.size, CornerRadius(radius), blendMode = BlendMode.Clear)
            drawRoundRect(berry, ring.topLeft, Size(ring.width, ring.height), CornerRadius(radius), style = Stroke(stroke))
        }
        val gap = with(LocalDensity.current) { 12.dp.toPx() }
        val edge = with(LocalDensity.current) { 12.dp.roundToPx() }
        Layout(content = { Bubble(page, c, stringResource(text), t) }, modifier = Modifier.fillMaxSize()) { m, cs ->
            val bw = minOf(300.dp.roundToPx(), cs.maxWidth - 2 * edge)
            val p = m.first().measure(Constraints(minWidth = bw, maxWidth = bw))
            layout(cs.maxWidth, cs.maxHeight) {
                val x = (ringTarget.center.x - bw / 2f).toInt().coerceIn(edge, cs.maxWidth - bw - edge)
                // Above the key when it sits in the lower half, below it otherwise.
                val y = if (ringTarget.center.y > cs.maxHeight / 2f) (ringTarget.top - gap - p.height).toInt()
                else (ringTarget.bottom + gap).toInt()
                p.place(x, y.coerceIn(edge, maxOf(edge, cs.maxHeight - p.height - edge)))
            }
        }
    }
}

@Composable
private fun Bubble(page: NativeLivePage, c: NativeLivePage.Coach, text: String, t: Tokens) {
    val last = c.at == c.steps.size - 1
    Column(
        Modifier.clip(R_CARD).background(t.panel).border(1.dp, t.line, R_CARD)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
    ) {
        Text(text, color = t.fg, fontSize = 15.sp, lineHeight = 21.sp, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${c.at + 1} / ${c.steps.size}", color = t.dim, fontSize = 12.sp, letterSpacing = 0.04.em,
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            )
            Box(
                Modifier.heightIn(min = 48.dp).clip(R_CTL).clickable(role = Role.Button) { page.endCoach(true) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.w_coach_skip), color = t.dim, fontSize = 14.sp, style = MaterialTheme.typography.labelLarge) }
            Box(
                Modifier.heightIn(min = 48.dp).padding(vertical = 4.dp).clip(R_CTL).background(t.berry)
                    .clickable(role = Role.Button, onClick = page::coachNext).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (last) R.string.w_coach_done else R.string.w_coach_next), color = t.onAccent,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** A short line near the top (the page's own toast). */
@Composable
internal fun BoxScope.PageToast(page: NativeLivePage) {
    val text = page.toast ?: return
    LaunchedEffect(text) { delay(1600); page.toast = null }
    Box(
        Modifier.align(Alignment.TopCenter).padding(top = (64 + page.insets.top).dp).clip(R_CHIP).background(GlassDeep)
            .border(1.dp, GlassLine, R_CHIP).padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) { Text(text, color = GlassFg, style = MaterialTheme.typography.bodyMedium) }
}
