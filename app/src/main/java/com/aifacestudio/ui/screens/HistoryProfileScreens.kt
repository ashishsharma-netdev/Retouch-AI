package com.aifacestudio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.aifacestudio.core.formatDateTime
import com.aifacestudio.data.local.ProjectEntity
import com.aifacestudio.data.model.ProjectStatus
import com.aifacestudio.ui.components.PrimaryButton
import com.aifacestudio.ui.components.ProjectRow
import com.aifacestudio.ui.components.SectionTitle
import com.aifacestudio.ui.navigation.Routes
import com.aifacestudio.ui.viewmodel.HistoryEvent
import com.aifacestudio.ui.viewmodel.HistoryViewModel
import com.aifacestudio.ui.viewmodel.ProfileViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryRoute(
    navController: NavHostController,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HistoryEvent.NavigateToPreview -> navController.navigate(Routes.preview(event.projectId))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Project History") },
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (projects.isEmpty()) {
                item {
                    Text(
                        "No processing history yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(projects, key = { it.projectId }) { project ->
                    HistoryProjectRow(
                        project = project,
                        onOpen = {
                            navController.navigate(
                                when (project.status) {
                                    ProjectStatus.COMPLETED -> Routes.result(project.projectId)
                                    ProjectStatus.PROCESSING -> Routes.processing(project.projectId)
                                    else -> Routes.preview(project.projectId)
                                }
                            )
                        },
                        onDuplicate = { viewModel.duplicate(project.projectId) },
                        onDelete = { viewModel.delete(project.projectId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryProjectRow(
    project: ProjectEntity,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ProjectRow(project = project, onOpen = onOpen)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDuplicate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                Text("Duplicate")
            }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Text("Delete")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileRoute(
    navController: NavHostController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val subscription by viewModel.subscriptionState.collectAsStateWithLifecycle()
    val exports by viewModel.exports.collectAsStateWithLifecycle()
    val storageUsed = exports.size * 12
    val storageProgress = (storageUsed / 500f).coerceIn(0f, 1f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = navController::popBackStack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                InfoCard(icon = Icons.Outlined.Person, title = "Account Information") {
                    Text("Guest creator", fontWeight = FontWeight.SemiBold)
                    Text("JWT authentication ready for backend login.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                InfoCard(icon = Icons.Outlined.WorkspacePremium, title = "Subscription Status") {
                    Text("${subscription.plan.label} Plan", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (subscription.priorityQueue) "Unlimited processing and priority queue enabled."
                        else "${subscription.usedThisMonth}/${subscription.monthlyLimit} monthly jobs used.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PrimaryButton(text = "Enable Premium Preview", onClick = viewModel.promoteToPremium, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                InfoCard(icon = Icons.Outlined.Storage, title = "Storage Usage") {
                    Text("$storageUsed MB of 500 MB", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { storageProgress }, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                InfoCard(icon = Icons.Outlined.Lock, title = "Privacy Settings") {
                    Text("Consent confirmation, audit logs, content reporting, and AI labeling are active.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionTitle("Export History") }
            if (exports.isEmpty()) {
                item {
                    Text("No exports yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(exports, key = { it.exportId }) { export ->
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(export.destination, fontWeight = FontWeight.SemiBold)
                            Text(export.exportedAt.formatDateTime(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "AI label: ${if (export.aiGeneratedLabel) "Yes" else "No"} • Metadata watermark: ${if (export.metadataWatermark) "Yes" else "No"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun InfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}
