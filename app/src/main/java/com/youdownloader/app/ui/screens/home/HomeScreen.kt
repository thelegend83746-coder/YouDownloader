package com.youdownloader.app.ui.screens.home

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.youdownloader.app.R
import com.youdownloader.app.data.model.VideoEntity
import com.youdownloader.app.ui.components.*
import com.youdownloader.app.ui.theme.RedPrimary
import com.youdownloader.app.util.StorageUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPlayer: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val completedVideos by viewModel.completedVideos.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()

    var videoToDelete by remember { mutableStateOf<VideoEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "You Downloader",
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                    }
                },
                actions = {
                    // Floating / Corner "+" Button
                    FilledIconButton(
                        onClick = { viewModel.openBottomSheet() },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = RedPrimary),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add Download", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (completedVideos.isEmpty() && activeDownloads.isEmpty()) {
                EmptyLibraryView(onAddClick = { viewModel.openBottomSheet() })
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                ) {
                    // Storage status card
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = RedPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Device Storage",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = "${uiState.freeStorageFormatted} free of ${uiState.totalStorageFormatted}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Section: Active Downloads
                    if (activeDownloads.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "DOWNLOADING (${activeDownloads.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = RedPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        items(activeDownloads, key = { it.id }) { video ->
                            ActiveDownloadCard(
                                video = video,
                                onPause = { viewModel.pauseDownload(it) },
                                onResume = { viewModel.resumeDownload(it) },
                                onCancel = { viewModel.cancelDownload(it) }
                            )
                        }
                    }

                    // Section: Completed Downloads Library
                    if (completedVideos.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "OFFLINE LIBRARY (${completedVideos.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        items(completedVideos, key = { it.id }) { video ->
                            CompletedVideoCard(
                                video = video,
                                onClick = { onNavigateToPlayer(video.id) },
                                onShare = {
                                    val shareIntent = StorageUtils.createShareVideoIntent(
                                        context = context,
                                        filePath = video.filePath,
                                        videoTitle = video.title
                                    )
                                    shareIntent?.let { context.startActivity(it) }
                                },
                                onDelete = { videoToDelete = video }
                            )
                        }
                    }
                }
            }

            // Bottom Sheet for adding link
            if (uiState.isSheetOpen) {
                AddDownloadBottomSheet(
                    isLoading = uiState.isLoadingPreview,
                    errorMessage = uiState.errorMessage,
                    previewInfo = uiState.previewInfo,
                    onFetchPreview = { url -> viewModel.fetchPreview(url) },
                    onDownload = { preview, quality -> viewModel.startDownload(preview, quality) },
                    onDismiss = { viewModel.closeBottomSheet() }
                )
            }

            // Delete confirmation dialog
            videoToDelete?.let { video ->
                ConfirmDeleteDialog(
                    videoTitle = video.title,
                    onConfirm = {
                        viewModel.deleteVideo(video)
                        videoToDelete = null
                    },
                    onDismiss = { videoToDelete = null }
                )
            }
        }
    }
}
