package com.aifacestudio.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.aifacestudio.core.formatBytes
import com.aifacestudio.core.formatDuration
import com.aifacestudio.data.model.ShareTarget
import com.aifacestudio.ui.components.MediaPreview
import com.aifacestudio.ui.components.PrimaryButton
import com.aifacestudio.ui.components.SectionTitle
import com.aifacestudio.ui.navigation.Routes
import com.aifacestudio.ui.viewmodel.ResultEvent
import com.aifacestudio.ui.viewmodel.ResultViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResultRoute(
    navController: NavHostController,
    viewModel: ResultViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ResultEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is ResultEvent.Share -> context.startActivity(event.intent)
                ResultEvent.NavigateHome -> navController.navigate(Routes.HOME) {
                    popUpTo(Routes.HOME) { inclusive = true }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Result") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigate(Routes.HOME) }) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Home")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::delete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                    }
                }
            )
        }
    ) { padding ->
        val details = state.details
        if (details == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center
            ) {
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
            MediaPreview(project = details.project, mediaFiles = details.mediaFiles, preferOutput = true)
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AI Generated Label Applied", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Visible label: ${if (details.project.visibleWatermark) "On" else "Off"} • Metadata watermark: ${if (details.project.metadataWatermark) "On" else "Off"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Processing time: ${details.project.processingTimeMs?.formatDuration() ?: "Unknown"} • Output size: ${(details.project.outputSizeBytes ?: 0L).formatBytes()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            SectionTitle("Save Options")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    text = if (state.saving) "Saving" else "Save",
                    onClick = { viewModel.save("Device Storage") },
                    modifier = Modifier.weight(1f),
                    enabled = !state.saving
                )
                OutlinedButton(
                    onClick = { viewModel.save("Gallery") },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    enabled = !state.saving
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Text("Gallery")
                }
            }
            SectionTitle("Cloud Storage")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Google Drive", "Dropbox", "OneDrive").forEach { label ->
                    OutlinedButton(onClick = { viewModel.share(ShareTarget.GENERIC) }) {
                        Text(label)
                    }
                }
            }
            SectionTitle("Share")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareTarget.entries.forEach { target ->
                    OutlinedButton(onClick = { viewModel.share(target) }) {
                        Icon(Icons.Outlined.IosShare, contentDescription = null)
                        Text(target.label)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { viewModel.save("Download Again") },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Text("Download Again")
                }
                OutlinedButton(
                    onClick = viewModel::reportContent,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Icon(Icons.Outlined.Flag, contentDescription = null)
                    Text("Report")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
