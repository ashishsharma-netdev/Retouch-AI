package com.aifacestudio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.ProjectStatus
import com.aifacestudio.ui.components.ActionCard
import com.aifacestudio.ui.components.ProjectRow
import com.aifacestudio.ui.components.SectionTitle
import com.aifacestudio.ui.navigation.Routes
import com.aifacestudio.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    navController: NavHostController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val projects by viewModel.recentProjects.collectAsStateWithLifecycle()
    val subscription by viewModel.subscriptionState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Face Studio") },
                actions = {
                    IconButton(onClick = { navController.navigate(Routes.HISTORY) }) {
                        Icon(Icons.Outlined.History, contentDescription = "History")
                    }
                    IconButton(onClick = { navController.navigate(Routes.PROFILE) }) {
                        Icon(Icons.Outlined.AccountCircle, contentDescription = "Profile")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "Create labeled face replacement media from files you own or have permission to use.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            item { SectionTitle("Quick Actions") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionCard(
                        title = "Image Face Replace",
                        subtitle = "JPG, PNG, WEBP up to 20 MB",
                        icon = Icons.Outlined.Image,
                        onClick = { navController.navigate(Routes.upload(MediaType.IMAGE)) }
                    )
                    ActionCard(
                        title = "Video Face Replace",
                        subtitle = "MP4, MOV, MKV up to 10 minutes",
                        icon = Icons.Outlined.Movie,
                        onClick = { navController.navigate(Routes.upload(MediaType.VIDEO)) }
                    )
                    ActionCard(
                        title = "GIF Face Replace",
                        subtitle = "GIF files up to 100 MB",
                        icon = Icons.Outlined.GifBox,
                        onClick = { navController.navigate(Routes.upload(MediaType.GIF)) }
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SectionTitle("Recent Projects")
                    Text(
                        "${projects.count { it.status == ProjectStatus.COMPLETED }} completed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (projects.isEmpty()) {
                item {
                    EmptyPanel(
                        title = "No projects yet",
                        body = "Start with an image, video, or GIF replacement."
                    )
                }
            } else {
                items(projects, key = { it.projectId }) { project ->
                    ProjectRow(
                        project = project,
                        onOpen = {
                            navController.navigate(
                                when (project.status) {
                                    ProjectStatus.COMPLETED -> Routes.result(project.projectId)
                                    ProjectStatus.PROCESSING -> Routes.processing(project.projectId)
                                    else -> Routes.preview(project.projectId)
                                }
                            )
                        }
                    )
                }
            }
            item { SectionTitle("Processing History") }
            item {
                ActionCard(
                    title = "View all jobs",
                    subtitle = "Open, duplicate, or delete past projects",
                    icon = Icons.Outlined.Collections,
                    onClick = { navController.navigate(Routes.HISTORY) }
                )
            }
            item { SectionTitle("Premium Features") }
            item {
                Card(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Outlined.WorkspacePremium, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${subscription.plan.label} Plan",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (subscription.priorityQueue) {
                                    "Priority queue, cloud backup, and high resolution exports enabled."
                                } else {
                                    "${subscription.usedThisMonth}/${subscription.monthlyLimit} monthly jobs used. Premium unlocks unlimited processing."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
