package io.github.realkhfan.foco.ui

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.realkhfan.foco.ScanController
import io.github.realkhfan.foco.scan.ExportFormat
import io.github.realkhfan.foco.scan.ExportNames
import io.github.realkhfan.foco.scan.ExportQuality
import io.github.realkhfan.foco.scan.PdfPageSize
import io.github.realkhfan.foco.scan.Quad
import io.github.realkhfan.foco.scan.ScanFilter
import io.github.realkhfan.foco.scan.ScanPage
import io.github.realkhfan.foco.ui.theme.FocoColors
import kotlin.math.hypot
import kotlin.math.min

// ------------------------------------------------------------------------------------- viewfinder

/** The page Foco found, outlined live in the viewfinder. */
@Composable
fun ScanOverlay(state: AppState) {
    val quad = state.scanQuad ?: return
    val primary = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxSize()) {
        val path = Path().apply {
            moveTo(quad[0] * size.width, quad[1] * size.height)
            for (i in 1 until 4) lineTo(quad[i * 2] * size.width, quad[i * 2 + 1] * size.height)
            close()
        }
        drawPath(path, primary.copy(alpha = 0.22f))
        drawPath(path, primary, style = Stroke(3.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
        for (i in 0 until 4) drawCircle(primary, 5.dp.toPx(), Offset(quad[i * 2] * size.width, quad[i * 2 + 1] * size.height))
    }
}

/** Scan: the last page with the page count; opens the review. */
@Composable
fun ScanStack(state: AppState, actions: CameraActions, iconRotation: Float) {
    val last = state.scanPages.lastOrNull() ?: return
    LaunchedEffect(last.key) { actions.scanImage(last, ScanController.THUMB) }
    val thumb = state.scanImages["${last.key}@${ScanController.THUMB}"]
    Box(
        Modifier
            .size(56.dp)
            .androidRotate(iconRotation)
            .clickable(onClick = actions::openScanReview)
            .semantics { contentDescription = "Scanned pages: ${state.scanPages.size}" },
    ) {
        Box(
            Modifier.size(52.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(10.dp)).background(Color.White)
                .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(
            Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("${state.scanPages.size}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun Modifier.androidRotate(degrees: Float) = this.rotate(degrees)

// ------------------------------------------------------------------------------------- corner editor

/**
 * Adjust the page outline: drag the four corners (a magnifier shows exactly where the corner is),
 * use the whole photo, or let Foco find the page again.
 */
@Composable
fun ScanCropScreen(edit: ScanEdit, actions: CameraActions) {
    BackHandler(onBack = actions::scanEditCancel)
    val primary = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val auto = remember(edit) { edit.page.corners.copyOf() }
    var dragging by remember { mutableIntStateOf(-1) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    val bitmap = edit.image
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    Column(
        Modifier.fillMaxSize().background(Color.Black)
            // Clear of the camera hole even though the status bar is hidden.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).navigationBarsPadding()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::scanEditCancel) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = Color.White) }
            Text(if (edit.isNew) "Adjust the corners" else "Crop page", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            val whole = edit.corners.contentEquals(Quad.full(1f, 1f, 0f))
            TextButton(onClick = { edit.corners = if (whole) auto.copyOf() else Quad.full(1f, 1f, 0f) }) {
                Icon(if (whole) Icons.Outlined.AutoFixHigh else Icons.Outlined.Fullscreen, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (whole) "Find page" else "Whole photo")
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            val scale = min(boxW / bitmap.width, boxH / bitmap.height)
            val iw = bitmap.width * scale
            val ih = bitmap.height * scale
            val left = (boxW - iw) / 2
            val top = (boxH - ih) / 2
            val touch = with(density) { 44.dp.toPx() }
            fun corner(i: Int) = Offset(left + edit.corners[i * 2] * iw, top + edit.corners[i * 2 + 1] * ih)
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(edit, iw, ih) {
                        detectDragGestures(
                            onDragStart = { p ->
                                val nearest = (0 until 4).minBy { hypot(corner(it).x - p.x, corner(it).y - p.y) }
                                dragging = if (hypot(corner(nearest).x - p.x, corner(nearest).y - p.y) < touch * 1.6f) nearest else -1
                                finger = p
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 },
                        ) { change, delta ->
                            val i = dragging
                            if (i < 0) return@detectDragGestures
                            change.consume()
                            finger = change.position
                            val c = edit.corners.copyOf()
                            c[i * 2] = (c[i * 2] + delta.x / iw).coerceIn(0f, 1f)
                            c[i * 2 + 1] = (c[i * 2 + 1] + delta.y / ih).coerceIn(0f, 1f)
                            edit.corners = c
                        }
                    },
            ) {
                drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(iw.toInt(), ih.toInt()))
                val quad = Path().apply {
                    moveTo(corner(0).x, corner(0).y)
                    for (i in 1 until 4) lineTo(corner(i).x, corner(i).y)
                    close()
                }
                // Dim everything outside the page.
                val outside = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(left, top, left + iw, top + ih))
                    addPath(quad)
                }
                drawPath(outside, Color.Black.copy(alpha = 0.55f))
                val good = Quad.isConvex(Quad.scale(edit.corners, iw, ih))
                drawPath(quad, if (good) primary else Color(0xFFE5483C), style = Stroke(2.5.dp.toPx()))
                for (i in 0 until 4) {
                    drawCircle(Color.White, 13.dp.toPx(), corner(i))
                    drawCircle(primary, 10.dp.toPx(), corner(i))
                }
                // Magnifier: shows the area under the finger, away from it.
                val d = dragging
                if (d >= 0) {
                    val r = 56.dp.toPx()
                    val zoom = 2.5f
                    val c = corner(d)
                    val center = Offset(if (c.x > size.width / 2) r + 8.dp.toPx() else size.width - r - 8.dp.toPx(), r + 8.dp.toPx())
                    val circle = Path().apply { addOval(Rect(center, r)) }
                    clipPath(circle) {
                        drawRect(Color.Black, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r))
                        withTransform({
                            translate(center.x - c.x * zoom, center.y - c.y * zoom)
                            scale(zoom, zoom, Offset.Zero)
                        }) {
                            drawImage(image, dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize(iw.toInt(), ih.toInt()))
                            drawPath(quad, primary, style = Stroke(2.dp.toPx() / zoom))
                        }
                    }
                    drawCircle(Color.White, r, center, style = Stroke(2.dp.toPx()))
                    val arm = 10.dp.toPx()
                    drawLine(primary, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(primary, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = actions::scanEditCancel) {
                Text(if (edit.isNew) "Retake" else "Cancel", color = Color.White, fontSize = 16.sp)
            }
            Button(
                onClick = actions::scanEditDone,
                enabled = Quad.isConvex(edit.corners),
                modifier = Modifier.height(52.dp),
            ) {
                Icon(Icons.Outlined.Check, null)
                Spacer(Modifier.width(8.dp))
                Text(if (edit.isNew) "Keep page" else "Done", fontSize = 16.sp)
            }
        }
    }
}

// ------------------------------------------------------------------------------------- review

/** All pages of the scan: look through them, fix them up and export. */
@Composable
fun ScanReviewScreen(state: AppState, actions: CameraActions) {
    val pages = state.scanPages.toList()
    if (pages.isEmpty()) return
    BackHandler {
        if (state.scanExportOpen && state.scanExportProgress == null) state.scanExportOpen = false else if (!state.scanExportOpen) actions.closeScanReview()
    }
    val pager = rememberPagerState(initialPage = state.scanReviewIndex.coerceIn(0, pages.lastIndex)) { pages.size }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { state.scanReviewIndex = it } }
    LaunchedEffect(state.scanReviewIndex, pages.size) {
        val target = state.scanReviewIndex.coerceIn(0, pages.lastIndex)
        if (target != pager.currentPage) pager.animateScrollToPage(target)
    }
    val current = pages[pager.currentPage.coerceIn(0, pages.lastIndex)]
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ScanPage?>(null) }
    val primary = MaterialTheme.colorScheme.primary

    Box(
        Modifier.fillMaxSize().background(Color(0xFF0E0F12))
            // Keep touches away from the viewfinder underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions::closeScanReview) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to camera", tint = Color.White) }
                Text(
                    if (pages.size == 1) "1 page" else "${pages.size} pages",
                    color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { confirmDiscard = true }) { Icon(Icons.Outlined.DeleteSweep, "Discard scan", tint = Color.White) }
                Button(
                    onClick = {
                        state.scanExportDone = null
                        // Keep the suggested name current (unless the user typed one).
                        val options = state.scanExportOptions
                        if (ExportNames.isDefault(options.name)) state.scanExportOptions = options.copy(name = ExportNames.defaultName())
                        state.scanExportOpen = true
                    },
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Icon(Icons.Outlined.SaveAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export")
                }
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), key = { pages[it].id }) { index ->
                val page = pages[index]
                LaunchedEffect(page.key) { actions.scanImage(page, ScanController.PREVIEW) }
                val bitmap = state.scanImages["${page.key}@${ScanController.PREVIEW}"]
                    ?: state.scanImages["${page.key}@${ScanController.THUMB}"]
                Box(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                    if (bitmap != null) {
                        Image(
                            bitmap.asImageBitmap(), "Page ${index + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CircularProgressIndicator(color = primary)
                    }
                }
            }
            Text(
                "Page ${pager.currentPage + 1} of ${pages.size}",
                color = FocoColors.Dim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FilterRow(current, actions)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                PageAction("Crop", Icons.Outlined.Crop) { actions.scanCrop(current) }
                PageAction("Rotate", Icons.Outlined.RotateRight) { actions.scanTurn(current) }
                PageAction("Earlier", Icons.AutoMirrored.Outlined.ArrowBack, enabled = pager.currentPage > 0) { actions.scanMove(current, -1) }
                PageAction("Later", Icons.AutoMirrored.Outlined.ArrowForward, enabled = pager.currentPage < pages.lastIndex) { actions.scanMove(current, 1) }
                PageAction("Delete", Icons.Outlined.Delete) { confirmDelete = current }
            }
            Spacer(Modifier.height(6.dp))
            val listState = rememberLazyListState()
            LaunchedEffect(pager.currentPage) { listState.animateScrollToItem(pager.currentPage.coerceAtLeast(0)) }
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(92.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                    LaunchedEffect(page.key) { actions.scanImage(page, ScanController.THUMB) }
                    val thumb = state.scanImages["${page.key}@${ScanController.THUMB}"]
                    val selected = index == pager.currentPage
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF2A2B2F))
                            .border(if (selected) 2.5.dp else 0.dp, if (selected) primary else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { state.scanReviewIndex = index },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumb != null) Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
                        Text(
                            "${index + 1}", color = Color.White, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(CircleShape).background(Color(0x99000000)).padding(horizontal = 5.dp),
                        )
                    }
                }
                item {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(0.72f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.5.dp, Color(0x66FFFFFF), RoundedCornerShape(8.dp))
                            .clickable(onClick = actions::closeScanReview)
                            .semantics { contentDescription = "Add pages" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, null, tint = Color.White) }
                }
            }
        }
        if (state.scanExportOpen) ScanExportSheet(state, actions)
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this scan?") },
            text = { Text("All ${pages.size} pages will be deleted. Pages you already exported are kept.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; actions.scanDiscard() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { page ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this page?") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; actions.scanDelete(page) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FilterRow(page: ScanPage, actions: CameraActions) {
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScanFilter.entries.forEach { filter ->
            val selected = page.filter == filter
            Text(
                if (filter == ScanFilter.BW) "B&W" else filter.label,
                color = if (selected) onPrimary else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) primary else FocoColors.ToggleBg)
                    .clickable { actions.scanSetFilter(page, filter, all = false) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
        Text(
            "To all",
            color = primary,
            fontSize = 13.sp,
            modifier = Modifier.clip(CircleShape).clickable { actions.scanSetFilter(page, page.filter, all = true) }
                .padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun PageAction(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (enabled) Color.White else Color(0x55FFFFFF))
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (enabled) Color.White else Color(0x55FFFFFF), fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------------------------- export

/** Export as PDF (one file) or JPG/PNG (one per page), then save, save somewhere else or share. */
@Composable
fun ScanExportSheet(state: AppState, actions: CameraActions) {
    var options by remember { mutableStateOf(state.scanExportOptions) }
    val busy = state.scanExportProgress != null
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!busy) state.scanExportOpen = false },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(FocoColors.Sheet)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Export", color = Color.White, fontSize = 22.sp)
            val done = state.scanExportDone
            if (done != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Text(done, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { state.scanExportDone = null }, modifier = Modifier.weight(1f)) { Text("Export again") }
                    Button(onClick = actions::scanNew, modifier = Modifier.weight(1f)) { Text("New scan") }
                }
                return@Column
            }
            OutlinedTextField(
                value = options.name,
                onValueChange = { options = options.copy(name = it) },
                label = { Text("File name") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Choice("Format", ExportFormat.entries.map { it.label }, options.format.ordinal, !busy) {
                options = options.copy(format = ExportFormat.entries[it])
            }
            if (options.format == ExportFormat.PDF) {
                Choice("Page size", PdfPageSize.entries.map { it.label }, options.pageSize.ordinal, !busy) {
                    options = options.copy(pageSize = PdfPageSize.entries[it])
                }
            }
            Choice("Quality", ExportQuality.entries.map { it.label }, options.quality.ordinal, !busy) {
                options = options.copy(quality = ExportQuality.entries[it])
            }
            val pages = state.scanPages.size
            Text(
                when {
                    options.format == ExportFormat.PDF -> "One PDF with $pages ${if (pages == 1) "page" else "pages"}"
                    pages == 1 -> "One ${options.format.label} picture"
                    else -> "$pages ${options.format.label} pictures, one per page"
                },
                color = FocoColors.Dim, fontSize = 13.sp,
            )
            val progress = state.scanExportProgress
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.scanExport(options, ExportTarget.SHARE) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share", maxLines = 1)
                }
                OutlinedButton(onClick = { actions.scanExport(options, ExportTarget.SAVE_TO) }, enabled = !busy, modifier = Modifier.weight(1.1f)) {
                    Icon(Icons.Outlined.FolderOpen, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save to…", maxLines = 1)
                }
                Button(onClick = { actions.scanExport(options, ExportTarget.SAVE) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.SaveAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save", maxLines = 1)
                }
            }
            Text(
                if (options.format == ExportFormat.PDF) "Save puts it in Documents/Foco Scans" else "Save puts them in Pictures/Foco Scans",
                color = FocoColors.Dim, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Choice(title: String, labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column {
        Text(title, color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}
