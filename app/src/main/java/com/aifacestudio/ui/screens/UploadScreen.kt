package com.aifacestudio.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.aifacestudio.core.MediaRules
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.MediaValidationResult
import com.aifacestudio.ui.components.PrimaryButton
import com.aifacestudio.ui.components.SectionTitle
import com.aifacestudio.ui.components.ValidationSummary
import com.aifacestudio.ui.navigation.Routes
import com.aifacestudio.ui.viewmodel.UploadEvent
import com.aifacestudio.ui.viewmodel.UploadViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadRoute(
    navController: NavHostController,
    viewModel: UploadViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pendingSourceCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingFaceCameraUri by remember { mutableStateOf<Uri?>(null) }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onSourceSelected)
    }
    val facePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onFaceSelected)
    }
    val addFacePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onAdditionalFacesSelected(uris)
    }
    val sourceCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingSourceCameraUri?.let(viewModel::onSourceSelected)
    }
    val faceCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingFaceCameraUri?.let(viewModel::onFaceSelected)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is UploadEvent.NavigateToPreview -> navController.navigate(Routes.preview(event.projectId))
                is UploadEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(state.mediaType.title) },
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionTitle("Source Upload")
            UploadPanel(
                title = "${state.mediaType.noun} file",
                result = state.sourceResult,
                validating = state.validatingSource,
                mediaType = state.mediaType,
                onGallery = { sourcePicker.launch(MediaRules.pickerMimeTypes(state.mediaType)) },
                onFilePicker = { sourcePicker.launch(MediaRules.pickerMimeTypes(state.mediaType)) },
                onCamera = {
                    val uri = createCaptureUri(context, "source")
                    pendingSourceCameraUri = uri
                    sourceCamera.launch(uri)
                },
                cameraEnabled = state.mediaType == MediaType.IMAGE
            )
            SectionTitle("Face Upload")
            FaceUploadPanel(
                results = state.faceResults,
                validating = state.validatingFace,
                onGallery = { facePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                onFilePicker = { facePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                onAddFaces = { addFacePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) },
                onRemoveFace = viewModel::removeFace,
                onCamera = {
                    val uri = createCaptureUri(context, "face")
                    pendingFaceCameraUri = uri
                    faceCamera.launch(uri)
                }
            )
            PrimaryButton(
                text = if (state.creatingDraft) "Preparing Preview" else "Preview Inputs",
                onClick = viewModel::createDraft,
                enabled = state.canPreview,
                modifier = Modifier.fillMaxWidth()
            )
            if (state.creatingDraft) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun UploadPanel(
    title: String,
    result: MediaValidationResult?,
    validating: Boolean,
    mediaType: MediaType,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onFilePicker: () -> Unit,
    cameraEnabled: Boolean
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            PreviewBox(result, mediaType)
            if (validating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Validating format, size, and face requirements.", style = MaterialTheme.typography.bodySmall)
            } else {
                ValidationSummary(result)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Gallery")
                }
                OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f), enabled = cameraEnabled) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Camera")
                }
                OutlinedButton(onClick = onFilePicker, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("File")
                }
            }
        }
    }
}

@Composable
private fun FaceUploadPanel(
    results: List<MediaValidationResult>,
    validating: Boolean,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onFilePicker: () -> Unit,
    onAddFaces: () -> Unit,
    onRemoveFace: (Int) -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Replacement face images", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            PreviewBox(results.firstOrNull(), MediaType.IMAGE)
            if (results.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    results.forEachIndexed { index, result ->
                        FaceQueueRow(index = index, result = result, onRemove = { onRemoveFace(index) })
                    }
                }
            }
            if (validating) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Validating format, size, and face requirements.", style = MaterialTheme.typography.bodySmall)
            } else {
                results.firstOrNull()?.let { ValidationSummary(it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Gallery")
                }
                OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Camera")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onFilePicker, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("File")
                }
                OutlinedButton(onClick = onAddFaces, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add")
                }
            }
        }
    }
}

@Composable
private fun FaceQueueRow(index: Int, result: MediaValidationResult, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            val uri = result.media?.uri
            if (uri != null) {
                AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Outlined.Face, contentDescription = null)
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Replacement ${index + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                result.media?.displayName ?: result.errors.firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = "Remove")
        }
    }
}

@Composable
private fun PreviewBox(result: MediaValidationResult?, mediaType: MediaType) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (mediaType == MediaType.VIDEO) 16f / 9f else 1f)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val uri = result?.media?.uri
        if (uri != null && mediaType != MediaType.VIDEO) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.VideoFile, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun createCaptureUri(context: Context, prefix: String): Uri {
    val directory = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File.createTempFile("${prefix}_", ".jpg", directory)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
