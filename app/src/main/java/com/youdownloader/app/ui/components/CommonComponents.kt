package com.youdownloader.app.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.youdownloader.app.R
import com.youdownloader.app.data.model.DownloadStatus
import com.youdownloader.app.data.model.VideoEntity
import com.youdownloader.app.data.model.VideoPreviewInfo
import com.youdownloader.app.data.model.VideoQuality
import com.youdownloader.app.ui.theme.RedPrimary
import com.youdownloader.app.ui.theme.StatusError
import com.youdownloader.app.ui.theme.StatusSuccess
import com.youdownloader.app.ui.theme.StatusWarning
import com.youdownloader.app.util.StorageUtils
import java.io.File
import com.youdownloader.app.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDownloadBottomSheet(
    isLoading: Boolean,
    errorMessage: String?,
    previewInfo: VideoPreviewInfo?,
    onFetchPreview: (String) -> Unit,
    onDownload: (VideoPreviewInfo, VideoQuality) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var urlText by remember { mutableStateOf("") }
    var selectedQuality by remember(previewInfo) {
        mutableStateOf(previewInfo?.availableQualities?.firstOrNull() ?: VideoQuality.Q_720P)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Download YouTube Video",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(14.dp))

            // URL Input Field + Paste Button
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste YouTube link (watch, shorts, youtu.be)") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val pasted = clip.getItemAt(0).text?.toString() ?: ""
                            if (pasted.isNotBlank()) {
                                urlText = pasted
                                onFetchPreview(pasted)
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "Paste",
                            tint = RedPrimary
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RedPrimary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Action button if not previewed yet
            if (previewInfo == null && !isLoading) {
                Button(
                    onClick = { if (urlText.isNotBlank()) onFetchPreview(urlText) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = RedPrimary)
                ) {
                    Text("Get Video Info", fontWeight = FontWeight.SemiBold)
                }
            }

            // Loading state
            if (isLoading) {
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = RedPrimary, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Fetching video details & streams…", style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Error display
            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = StatusError)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Preview Info & Quality Selector
            if (previewInfo != null) {
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Thumbnail
                        Box(
                            modifier = Modifier
                                .size(width = 110.dp, height = 75.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(previewInfo.thumbnailUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Video Thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Duration badge
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = Formatters.formatDuration(previewInfo.durationMs),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Video Title & Channel
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = previewInfo.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = previewInfo.channel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Select Quality (with sound included):",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quality Chips Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    previewInfo.availableQualities.forEach { quality ->
                        FilterChip(
                            selected = selectedQuality == quality,
                            onClick = { selectedQuality = quality },
                            label = { Text(quality.label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = RedPrimary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Download & Cancel Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = { onDownload(previewInfo, selectedQuality) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = RedPrimary)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveDownloadCard(
    video: VideoEntity,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isPaused = video.downloadStatus == DownloadStatus.PAUSED
    val isFailed = video.downloadStatus == DownloadStatus.FAILED

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Thumbnail
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(video.thumbnailPath.ifBlank { "https://img.youtube.com/vi/${video.youtubeId}/hqdefault.jpg" })
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 80.dp, height = 55.dp)
                        .clip(RoundedCornerShape(8.dp))
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when {
                            isFailed -> "Failed: ${video.errorMessage ?: "Unknown error"}"
                            isPaused -> "Paused (${video.progressPercent}%)"
                            else -> "Downloading: ${video.progressPercent}% • ${video.quality}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            isFailed -> StatusError
                            isPaused -> StatusWarning
                            else -> RedPrimary
                        }
                    )
                }

                // Action Controls
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isPaused) {
                        IconButton(onClick = { onResume(video.id) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = RedPrimary)
                        }
                    } else if (!isFailed) {
                        IconButton(onClick = { onPause(video.id) }) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    } else {
                        IconButton(onClick = { onResume(video.id) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = RedPrimary)
                        }
                    }

                    IconButton(onClick = { onCancel(video.id) }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { (video.progressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = if (isPaused) StatusWarning else RedPrimary,
                trackColor = MaterialTheme.colorScheme.surface
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Data size summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${Formatters.formatFileSize(video.bytesDownloaded)} / ${Formatters.formatFileSize(video.fileSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${video.progressPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CompletedVideoCard(
    video: VideoEntity,
    onClick: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with Play overlay
            Box(
                modifier = Modifier
                    .size(width = 110.dp, height = 75.dp)
                    .clip(RoundedCornerShape(10.dp))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(video.thumbnailPath.ifBlank { "https://img.youtube.com/vi/${video.youtubeId}/hqdefault.jpg" })
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Play icon pill
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Duration badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = Formatters.formatDuration(video.durationMs),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Metadata
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = video.channel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = video.quality,
                        style = MaterialTheme.typography.labelSmall,
                        color = RedPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " • ${Formatters.formatFileSize(video.fileSizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Options 3-dots Menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Play in External Player") },
                        leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            StorageUtils.openVideoWithExternalPlayer(context, File(video.filePath))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share Video") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = StatusError) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = StatusError) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyLibraryView(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.VideoLibrary,
            contentDescription = null,
            tint = RedPrimary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No videos downloaded yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tap the + button to paste a YouTube link and save videos for offline viewing with sound.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onAddClick,
            colors = ButtonDefaults.buttonColors(containerColor = RedPrimary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add YouTube Link", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ConfirmDeleteDialog(
    videoTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Video?", fontWeight = FontWeight.Bold) },
        text = {
            Text("Are you sure you want to delete \"$videoTitle\"? The downloaded video file will be permanently removed from your device.")
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = StatusError)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
