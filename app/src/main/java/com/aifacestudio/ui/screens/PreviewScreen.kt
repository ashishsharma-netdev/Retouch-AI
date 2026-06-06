package com.aifacestudio.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.aifacestudio.data.local.MediaFileEntity
import com.aifacestudio.data.model.ExportQuality
import com.aifacestudio.data.model.ImageFormat
import com.aifacestudio.data.model.MediaRole
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.VideoResolution
import com.aifacestudio.ui.components.MediaPreview
import com.aifacestudio.ui.components.PrimaryButton
import com.aifacestudio.ui.components.SecondaryButton
import com.aifacestudio.ui.components.SectionTitle
import com.aifacestudio.ui.navigation.Routes
import com.aifacestudio.ui.viewmodel.PreviewEvent
import com.aifacestudio.ui.viewmodel.PreviewSettings
import com.aifacestudio.ui.viewmodel.PreviewViewModel
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewRoute(
    navController: NavHostController,
    viewModel: PreviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PreviewEvent.NavigateToProcessing -> navController.navigate(Routes.processing(event.projectId)) {
                    popUpTo(Routes.PREVIEW) { inclusive = false }
                }
                is PreviewEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Preview Inputs") },
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        val details = state.details
        if (details == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("Source Preview")
            MediaPreview(project = details.project, mediaFiles = details.mediaFiles, preferOutput = false)
            SectionTitle("Face Preview")
            FacePreview(details.mediaFiles.filter { it.role == MediaRole.FACE })
            SectionTitle("Processing Settings")
            SettingsCard(
                mediaType = details.project.mediaType,
                settings = state.settings,
                sourceDurationMs = details.mediaFiles.firstOrNull { it.role == MediaRole.SOURCE }?.durationMs,
                onSettingsChanged = viewModel::updateSettings
            )
            ConsentCard(
                checked = state.settings.consentAccepted,
                onChecked = { checked ->
                    viewModel.updateSettings { it.copy(consentAccepted = checked) }
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(text = "Cancel", onClick = navController::popBackStack, modifier = Modifier.weight(1f))
                PrimaryButton(
                    text = if (state.starting) "Starting" else "Replace Face",
                    onClick = viewModel::startProcessing,
                    enabled = !state.starting,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FacePreview(faces: List<MediaFileEntity>) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                val primaryFace = faces.firstOrNull()
                if (primaryFace != null) {
                    AsyncImage(
                        model = Uri.parse(primaryFace.uri),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Outlined.Face, contentDescription = null)
                }
            }
            if (faces.size > 1) {
                faces.forEachIndexed { index, face ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Replacement ${index + 1}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            face.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    mediaType: MediaType,
    settings: PreviewSettings,
    sourceDurationMs: Long?,
    onSettingsChanged: ((PreviewSettings) -> PreviewSettings) -> Unit
) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (mediaType == MediaType.IMAGE) {
                SettingLabel("Image Format")
                EnumChips(
                    values = ImageFormat.entries,
                    selected = settings.imageFormat,
                    label = { it.label },
                    onSelected = { value -> onSettingsChanged { it.copy(imageFormat = value) } }
                )
            }
            SettingLabel("Quality")
            EnumChips(
                values = if (mediaType == MediaType.GIF) listOf(ExportQuality.STANDARD, ExportQuality.HIGH) else ExportQuality.entries,
                selected = settings.quality,
                label = { it.label },
                onSelected = { value -> onSettingsChanged { it.copy(quality = value) } }
            )
            if (mediaType == MediaType.VIDEO) {
                SettingLabel("Output Resolution")
                EnumChips(
                    values = VideoResolution.entries,
                    selected = settings.videoResolution,
                    label = { it.label },
                    onSelected = { value -> onSettingsChanged { it.copy(videoResolution = value) } }
                )
                SettingLabel("FPS")
                EnumChips(
                    values = listOf(24, 30, 60),
                    selected = settings.fps,
                    label = { it.toString() },
                    onSelected = { value -> onSettingsChanged { it.copy(fps = value) } }
                )
                VideoTrimRange(
                    durationMs = sourceDurationMs,
                    settings = settings,
                    onSettingsChanged = onSettingsChanged
                )
            }
            ToggleRow(
                title = "Multi Face Replacement",
                checked = settings.multiFace,
                onChecked = { checked -> onSettingsChanged { it.copy(multiFace = checked) } }
            )
            ToggleRow(
                title = "Visible Watermark",
                checked = settings.visibleWatermark,
                onChecked = { checked -> onSettingsChanged { it.copy(visibleWatermark = checked) } }
            )
            ToggleRow(
                title = "Metadata Watermark",
                checked = settings.metadataWatermark,
                onChecked = { checked -> onSettingsChanged { it.copy(metadataWatermark = checked) } }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoTrimRange(
    durationMs: Long?,
    settings: PreviewSettings,
    onSettingsChanged: ((PreviewSettings) -> PreviewSettings) -> Unit
) {
    val maxDurationMs = durationMs?.takeIf { it > 1_000L } ?: return
    val maxSeconds = (maxDurationMs / 1000f).coerceAtLeast(1f)
    val startSeconds = (settings.videoTrimStartMs / 1000f).coerceIn(0f, (maxSeconds - 1f).coerceAtLeast(0f))
    val endSeconds = ((settings.videoTrimEndMs.takeIf { it > 0L } ?: maxDurationMs) / 1000f).coerceIn(startSeconds + 1f, maxSeconds)

    SettingLabel("Video Segment")
    RangeSlider(
        value = startSeconds..endSeconds,
        onValueChange = { range ->
            val startMs = (range.start.coerceIn(0f, maxSeconds) * 1000f).roundToLong()
            val endMs = (range.endInclusive.coerceIn(range.start + 1f, maxSeconds) * 1000f).roundToLong()
            onSettingsChanged { it.copy(videoTrimStartMs = startMs, videoTrimEndMs = endMs) }
        },
        valueRange = 0f..maxSeconds,
        steps = maxOf(0, maxSeconds.toInt() - 1)
    )
    Text(
        "${settings.videoTrimStartMs.formatTrimTime()} to ${(settings.videoTrimEndMs.takeIf { it > 0L } ?: maxDurationMs).formatTrimTime()} of ${maxDurationMs.formatTrimTime()}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun Long.formatTrimTime(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun ConsentCard(checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(checked = checked, onCheckedChange = onChecked)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Consent Confirmation", fontWeight = FontWeight.SemiBold)
                Text(
                    "I own or have permission to use this media and face image. I will not use the result to impersonate, harass, defraud, or create non-consensual content.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun <T> EnumChips(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                label = { Text(label(value)) }
            )
        }
    }
}
