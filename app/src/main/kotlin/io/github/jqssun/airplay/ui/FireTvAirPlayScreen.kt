package io.github.jqssun.airplay.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.viewmodel.MainViewModel

/**
 * Minimalist Amazon Fire TV Stick AirPlay EQ Application Screen.
 *
 * Requirements:
 * - 100% deep black (#000000) OLED-friendly design.
 *
 * ZUSTAND 1: NICHT VERBUNDEN (IDLE)
 * - Seite 0: Minimalistischer Verbindungs-Bildschirm (Startansicht, kein Logo).
 *            D-Pad RECHTS -> wechselt zu Seite 1 (Pre-Equalizer).
 * - Seite 1: Pre-Equalizer-Menü mit 5 Frequenzbändern (60Hz, 230Hz, 910Hz, 4kHz, 14kHz).
 *            D-Pad Links/Rechts: Bandwahl. D-Pad Hoch/Runter: -15 dB bis +15 dB.
 *            SONDERREGEL: Bei Band 0 und Druck nach LINKS -> zurück zu Seite 0.
 *
 * ZUSTAND 2: VERBUNDEN (AKTIVER AIRPLAY-STREAM)
 * - Automatische Aktivierung bei eingehendem Stream.
 * - Seite A: Now Playing (Album-Cover 24dp Radius falls vorhanden; sonst reines Schwarz mit Typo).
 * - Seite B: Pure Blackscreen (100% tiefschwarz, keinerlei UI).
 * - Seite C: Visualizer-Screen (32-Band CustomEqualizerView).
 * - GLOBALE EQ-STEUERUNG: D-Pad RUNTER öffnet schwebendes Equalizer-Overlay.
 * - ZURÜCK-Taste: Schließt das Overlay; verlässt erst die App, wenn kein Overlay offen ist.
 */
@Composable
fun FireTvAirPlayScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val connections by viewModel.connectionCount.collectAsState()
    val serverState by viewModel.serverState.collectAsState()
    val serverName by viewModel.serverName.collectAsState()
    val track by viewModel.trackInfo.collectAsState()
    val bandLevels by viewModel.bandLevels.collectAsState()
    val bandNames by viewModel.bandNames.collectAsState()

    // Ensure AirPlay server is active on Fire TV
    LaunchedEffect(Unit) {
        if (serverState == AirPlayService.ServerState.STOPPED) {
            viewModel.startServer()
        }
    }

    val isConnected = connections > 0

    // Zustand 1: Idle (0 = Connect, 1 = Pre-EQ)
    var idlePage by remember { mutableStateOf(0) }

    // Zustand 2: Connected (0 = Now Playing, 1 = Blackscreen, 2 = Visualizer)
    var connectedPage by remember { mutableStateOf(0) }
    var showEqualizerOverlay by remember { mutableStateOf(false) }

    // Equalizer Band selection (0 to 4)
    var selectedBandIndex by remember { mutableStateOf(0) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isConnected, idlePage, showEqualizerOverlay) {
        focusRequester.requestFocus()
    }

    // Intercept Back button when Equalizer Overlay is active
    BackHandler(enabled = showEqualizerOverlay) {
        showEqualizerOverlay = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false

                if (!isConnected) {
                    // ============================================
                    // ZUSTAND 1: IDLE NAVIGATION & EQ
                    // ============================================
                    when (idlePage) {
                        0 -> {
                            if (event.key == Key.DirectionRight) {
                                idlePage = 1
                                selectedBandIndex = 0
                                true
                            } else false
                        }
                        1 -> {
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    if (selectedBandIndex == 0) {
                                        // SONDERREGEL: Band 0 + LINKS -> zurück zu Seite 0
                                        idlePage = 0
                                    } else {
                                        selectedBandIndex--
                                    }
                                    true
                                }
                                Key.DirectionRight -> {
                                    if (selectedBandIndex < 4) {
                                        selectedBandIndex++
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    viewModel.adjustEqualizerBandLevelDb(selectedBandIndex.toShort(), +1)
                                    true
                                }
                                Key.DirectionDown -> {
                                    viewModel.adjustEqualizerBandLevelDb(selectedBandIndex.toShort(), -1)
                                    true
                                }
                                Key.DirectionCenter, Key.Enter -> {
                                    viewModel.setEqualizerBandLevel(selectedBandIndex.toShort(), 0)
                                    true
                                }
                                else -> false
                            }
                        }
                        else -> false
                    }
                } else {
                    // ============================================
                    // ZUSTAND 2: CONNECTED NAVIGATION & OVERLAY
                    // ============================================
                    if (showEqualizerOverlay) {
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (selectedBandIndex > 0) selectedBandIndex--
                                true
                            }
                            Key.DirectionRight -> {
                                if (selectedBandIndex < 4) selectedBandIndex++
                                true
                            }
                            Key.DirectionUp -> {
                                viewModel.adjustEqualizerBandLevelDb(selectedBandIndex.toShort(), +1)
                                true
                            }
                            Key.DirectionDown -> {
                                viewModel.adjustEqualizerBandLevelDb(selectedBandIndex.toShort(), -1)
                                true
                            }
                            Key.DirectionCenter, Key.Enter -> {
                                viewModel.setEqualizerBandLevel(selectedBandIndex.toShort(), 0)
                                true
                            }
                            Key.Back -> {
                                showEqualizerOverlay = false
                                true
                            }
                            else -> false
                        }
                    } else {
                        // When overlay is hidden: Horizontal navigation between A, B, C
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (connectedPage > 0) connectedPage--
                                true
                            }
                            Key.DirectionRight -> {
                                if (connectedPage < 2) connectedPage++
                                true
                            }
                            Key.DirectionDown -> {
                                // Global Trigger: D-Pad RUNTER öffnet das Equalizer-Overlay
                                showEqualizerOverlay = true
                                true
                            }
                            else -> false
                        }
                    }
                }
            }
    ) {
        if (!isConnected) {
            // ========================================================
            // ZUSTAND 1: NICHT VERBUNDEN (IDLE)
            // ========================================================
            if (idlePage == 0) {
                // Seite 0: Minimalistischer Verbindungs-Bildschirm
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 48.dp)
                    ) {
                        Text(
                            text = "AIRPLAY EQ",
                            color = Color(0xFF00FF88),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = serverName.ifBlank { "Fire TV Stick" },
                            color = Color.White,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Bereit für Verbindung • Wähle dieses Gerät auf deinem Apple-Gerät",
                            color = Color(0xFFAAAAAA),
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(36.dp))
                        // Diskreter Navigation-Hinweis
                        Text(
                            text = "D-Pad ► Pre-Equalizer einstellen",
                            color = Color(0x66FFFFFF),
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                // Seite 1: Pre-Equalizer-Menü
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PRE-EQUALIZER (5-BAND DSP)",
                            color = Color(0xFF00FF88),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            text = "◄ bei Band 0: Zurück zu Start  |  ▲ ▼ dB regeln  |  OK: Reset",
                            color = Color(0xFFAAAAAA),
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    EqualizerBandsRow(
                        bandLevels = bandLevels,
                        bandNames = bandNames,
                        selectedBandIndex = selectedBandIndex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        } else {
            // ========================================================
            // ZUSTAND 2: VERBUNDEN (AKTIVER AIRPLAY-STREAM)
            // ========================================================
            when (connectedPage) {
                0 -> {
                    // Seite A: Now Playing
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (track.coverArt != null) {
                            // Mit Album-Cover (24dp Radius)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(32.dp)
                            ) {
                                Image(
                                    bitmap = track.coverArt!!.asImageBitmap(),
                                    contentDescription = "Cover",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(280.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Text(
                                    text = track.title.ifBlank { "AirPlay Audio" },
                                    color = Color.White,
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = track.artist.ifBlank { "Verbunden" },
                                    color = Color(0xFF00E5FF),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (track.album.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = track.album,
                                        color = Color(0xFF777777),
                                        fontSize = 15.sp,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        } else {
                            // OHNE Cover: KEIN Platzhalter, nur zentrierte Typo auf tiefschwarz (#000000)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(horizontal = 48.dp)
                            ) {
                                Text(
                                    text = track.title.ifBlank { "AirPlay Stream" },
                                    color = Color.White,
                                    fontSize = 42.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = track.artist.ifBlank { "Verbunden" },
                                    color = Color(0xFF00E5FF),
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (track.album.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = track.album,
                                        color = Color(0xFF777777),
                                        fontSize = 18.sp,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Dezent unten: Seiten-Indikator & EQ-Hinweis
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 20.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "◄ Now Playing  |  Blackscreen  |  Visualizer ►  •  ▼ Equalizer",
                                color = Color(0x55FFFFFF),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                1 -> {
                    // Seite B: Pure Blackscreen (100% tiefschwarz, keinerlei UI)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    )
                }
                2 -> {
                    // Seite C: Visualizer-Screen (32-Band CustomEqualizerView)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                CustomEqualizerView(ctx).apply {
                                    bandDataProvider = { outBands ->
                                        viewModel.getVisualizerBands(outBands)
                                    }
                                }
                            },
                            update = { view ->
                                view.bandDataProvider = { outBands ->
                                    viewModel.getVisualizerBands(outBands)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Dezent oben rechts: Visualizer Indikator
                        Text(
                            text = "32-BAND FFT • 60 FPS",
                            color = Color(0x44FFFFFF),
                            fontSize = 11.sp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                        )
                    }
                }
            }

            // ========================================================
            // SCHWEBENDES EQUALIZER-OVERLAY (D-Pad RUNTER)
            // ========================================================
            AnimatedVisibility(
                visible = showEqualizerOverlay,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xF20C0C0C)), // 95% dark
                    border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LIVE SOFTWARE DSP EQUALIZER",
                                color = Color(0xFF00E5FF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "◄ ► Band  |  ▲ ▼ dB  |  OK Reset  |  ↩ Zurück schließt",
                                color = Color(0xFFAAAAAA),
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        EqualizerBandsRow(
                            bandLevels = bandLevels,
                            bandNames = bandNames,
                            selectedBandIndex = selectedBandIndex,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reusable row of the 5 interactive frequency bands.
 */
@Composable
private fun EqualizerBandsRow(
    bandLevels: List<Short>,
    bandNames: List<String>,
    selectedBandIndex: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 5) {
            val levelMb = bandLevels.getOrElse(i) { 0.toShort() }
            val levelDb = (levelMb / 100)
            val name = bandNames.getOrElse(i) { "Band $i" }
            val isFocused = selectedBandIndex == i

            val subtitle = when (i) {
                0 -> "Bass"
                1 -> "Mid-Bass"
                2 -> "Mid"
                3 -> "Mid-High"
                4 -> "Treble"
                else -> ""
            }

            EqualizerBandBar(
                index = i,
                name = name,
                subtitle = subtitle,
                levelDb = levelDb,
                isSelected = isFocused,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Single vertical band bar with visible D-pad focus highlight.
 */
@Composable
private fun EqualizerBandBar(
    index: Int,
    name: String,
    subtitle: String,
    levelDb: Int,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        if (isSelected) Color(0xFF00E5FF) else Color(0xFF222222),
        label = "eqBarBorder"
    )
    val bgColor by animateColorAsState(
        if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF111111),
        label = "eqBarBg"
    )

    // Normalize -15 dB to +15 dB -> 0.0 to 1.0 (center 0 dB = 0.5)
    val progressNorm = ((levelDb + 15f) / 30f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(progressNorm, label = "eqProgress")

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(if (isSelected) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Live dB value
            Text(
                text = if (levelDb > 0) "+$levelDb dB" else "$levelDb dB",
                color = if (isSelected) Color(0xFF00E5FF) else if (levelDb != 0) Color(0xFF00FF88) else Color(0xFFAAAAAA),
                fontSize = 13.sp,
                fontWeight = if (isSelected || levelDb != 0) FontWeight.Bold else FontWeight.Medium
            )

            // Vertical slider track
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF222222))
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                // Active level bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(animatedProgress)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF00E5FF), // Top: Electric cyan
                                    Color(0xFF00FF88)  // Bottom: Spring green
                                )
                            )
                        )
                )

                // 0 dB center tick
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.Center)
                        .background(Color.White.copy(alpha = 0.4f))
                )
            }

            // Frequency and Band Name
            Text(
                text = "$name\n$subtitle",
                color = if (isSelected) Color.White else Color(0xFF888888),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
