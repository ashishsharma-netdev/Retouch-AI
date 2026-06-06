package com.aifacestudio.ui.components

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.aifacestudio.core.dimensionLabel
import com.aifacestudio.core.formatBytes
import com.aifacestudio.core.formatDuration
import com.aifacestudio.data.local.MediaFileEntity
import com.aifacestudio.data.local.ProjectEntity
import com.aifacestudio.data.model.MediaRole
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.MediaValidationResult
import com.aifacestudio.data.model.ProjectStatus

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

@Composable
fun ActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ProjectRow(
    project: ProjectEntity,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (project.mediaType == MediaType.VIDEO) Icons.Outlined.Movie else Icons.Outlined.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f)) {
                Text(project.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    "${project.mediaType.noun} • ${project.status.name.lowercase().replaceFirstChar { it.titlecase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusChip(project.status)
            trailing?.invoke()
        }
    }
}

@Composable
fun StatusChip(status: ProjectStatus) {
    val (label, icon, color) = when (status) {
        ProjectStatus.COMPLETED -> Triple("Done", Icons.Outlined.CheckCircle, MaterialTheme.colorScheme.primary)
        ProjectStatus.PROCESSING -> Triple("Live", Icons.Outlined.HourglassTop, MaterialTheme.colorScheme.tertiary)
        ProjectStatus.FAILED -> Triple("Failed", Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error)
        ProjectStatus.CANCELED -> Triple("Canceled", Icons.Outlined.WarningAmber, MaterialTheme.colorScheme.secondary)
        ProjectStatus.DRAFT -> Triple("Draft", Icons.Outlined.HourglassTop, MaterialTheme.colorScheme.outline)
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color) }
    )
}

@Composable
fun ValidationSummary(result: MediaValidationResult?) {
    if (result == null) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        result.media?.let { media ->
            Text(
                media.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    media.sizeBytes.formatBytes(),
                    media.width.dimensionLabel(media.height),
                    media.durationMs?.formatDuration()
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        result.errors.forEach { MessageLine(it, isError = true) }
        result.warnings.forEach { MessageLine(it, isError = false) }
        if (result.isValid && result.warnings.isEmpty()) {
            MessageLine("Validation passed.", isError = false)
        }
    }
}

@Composable
private fun MessageLine(text: String, isError: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ChoiceRow(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelected(option) },
                label = { Text(option, maxLines = 1) }
            )
        }
    }
}

@Composable
fun MediaPreview(
    project: ProjectEntity?,
    mediaFiles: List<MediaFileEntity>,
    preferOutput: Boolean,
    modifier: Modifier = Modifier
) {
    val uri = when {
        preferOutput && project?.outputUri != null -> Uri.parse(project.outputUri)
        project != null -> Uri.parse(project.sourceUri)
        else -> null
    }
    val mediaType = project?.mediaType ?: mediaFiles.firstOrNull { it.role == MediaRole.SOURCE }?.let {
        when {
            it.mimeType?.startsWith("video/") == true -> MediaType.VIDEO
            it.mimeType == "image/gif" -> MediaType.GIF
            else -> MediaType.IMAGE
        }
    } ?: MediaType.IMAGE

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(if (mediaType == MediaType.VIDEO) 16f / 9f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (uri == null) {
            CircularProgressIndicator()
        } else if (mediaType == MediaType.VIDEO) {
            VideoPlayer(uri)
        } else {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (preferOutput) {
            Text(
                "AI GENERATED",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color(0x99000000), RoundedCornerShape(topStart = 8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun VideoPlayer(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                useController = true
                this.player = player
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(onClick = onClick, modifier = modifier.height(48.dp), enabled = enabled) {
        Text(text)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(48.dp), enabled = enabled) {
        Text(text)
    }
}

@Composable
fun ElevatedActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    ElevatedButton(onClick = onClick, modifier = modifier.height(48.dp), enabled = enabled) {
        Text(text)
    }
}
