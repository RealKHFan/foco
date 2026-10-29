package io.github.realkhfan.foco

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.realkhfan.foco.camera.CameraEngine
import io.github.realkhfan.foco.scan.ExportFormat
import io.github.realkhfan.foco.scan.ExportNames
import io.github.realkhfan.foco.scan.ExportOptions
import io.github.realkhfan.foco.scan.ScanExporter
import io.github.realkhfan.foco.scan.ScanFilter
import io.github.realkhfan.foco.scan.ScanPage
import io.github.realkhfan.foco.scan.ScanStore
import io.github.realkhfan.foco.ui.AppState
import io.github.realkhfan.foco.ui.ExportTarget
import io.github.realkhfan.foco.ui.ScanEdit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Document scanning: taking pages, adjusting them, and exporting the result. */
class ScanController(
    private val activity: ComponentActivity,
    private val state: AppState,
    private val engine: CameraEngine,
    private val haptic: () -> Unit,
    private val playShutter: () -> Unit,
    /** Opens a system "save as" / folder picker and calls back with the chosen place (null if cancelled). */
    private val pickPlace: (Intent, (Uri?) -> Unit) -> Unit,
) {
    private val store = ScanStore(activity)
    private val exporter = ScanExporter(activity, store)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val renderThread = Dispatchers.Default.limitedParallelism(1)
    /** All writes to the stored pages, one after another, so an older page list can never win over a newer one. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val storeThread = Dispatchers.IO.limitedParallelism(1)
    private val pending = mutableSetOf<String>()

    /** Brings back the pages of an unfinished scan. */
    fun load() {
        if (state.secure) return // scans aren't shown on the lock screen
        activity.lifecycleScope.launch {
            val pages = withContext(storeThread) { store.load() }
            state.scanPages.clear()
            state.scanPages.addAll(pages)
        }
    }

    fun capture() {
        if (state.scanBusy || state.scanEdit != null) return
        if (state.scanPages.size >= MAX_PAGES) {
            state.message = "A scan can have up to $MAX_PAGES pages"
            return
        }
        state.scanBusy = true
        state.flashTick++
        playShutter()
        haptic()
        engine.takeScanPhoto { jpeg, rotation, focal ->
            if (jpeg == null) {
                state.scanBusy = false
                state.message = "Couldn't take the picture"
                return@takeScanPhoto
            }
            activity.lifecycleScope.launch {
                val result = withContext(storeThread) {
                    runCatching {
                        val page = store.add(jpeg, rotation, focal)
                        page to store.upright(page, EDIT_SIZE)
                    }.getOrNull()
                }
                state.scanBusy = false
                val (page, image) = result ?: run {
                    state.message = "Couldn't read the picture"
                    return@launch
                }
                if (image == null) {
                    state.message = "Couldn't read the picture"
                    return@launch
                }
                state.scanEdit = ScanEdit(page, image, page.corners, isNew = true)
            }
        }
    }

    fun editDone() {
        val edit = state.scanEdit ?: return
        val page = edit.page.edited(corners = ScanStore.clampCorners(edit.corners), turn = edit.turn)
        if (edit.isNew) {
            state.scanPages += page
            state.scanReviewIndex = state.scanPages.lastIndex
        } else {
            replace(edit.page, page)
        }
        state.scanEdit = null
        haptic()
        persist()
        image(page, THUMB)
    }

    fun editCancel() {
        val edit = state.scanEdit ?: return
        state.scanEdit = null
        if (edit.isNew) persist() // drops the photo that wasn't kept
    }

    fun openReview() {
        if (state.scanPages.isEmpty()) return
        state.scanReviewIndex = state.scanReviewIndex.coerceIn(0, state.scanPages.lastIndex)
        state.scanExportDone = null
        state.scanReviewOpen = true
    }

    fun closeReview() {
        state.scanReviewOpen = false
        state.scanExportOpen = false
    }

    fun setFilter(page: ScanPage, filter: ScanFilter, all: Boolean) {
        if (all) {
            state.scanPages.toList().forEach { if (it.filter != filter) replace(it, it.edited(filter = filter)) }
        } else if (page.filter != filter) {
            replace(page, page.edited(filter = filter))
        }
        persist()
    }

    fun turn(page: ScanPage) {
        replace(page, page.edited(turn = page.turn + 90))
        persist()
    }

    fun delete(page: ScanPage) {
        val index = state.scanPages.indexOfFirst { it.id == page.id }
        if (index < 0) return
        state.scanPages.removeAt(index)
        dropImages(page.id)
        persist()
        if (state.scanPages.isEmpty()) closeReview() else state.scanReviewIndex = index.coerceAtMost(state.scanPages.lastIndex)
    }

    fun move(page: ScanPage, delta: Int) {
        val index = state.scanPages.indexOfFirst { it.id == page.id }
        val target = index + delta
        if (index < 0 || target !in state.scanPages.indices) return
        val list = state.scanPages
        val moved = list.removeAt(index)
        list.add(target, moved)
        state.scanReviewIndex = target
        persist()
    }

    fun crop(page: ScanPage) {
        activity.lifecycleScope.launch {
            val image = withContext(Dispatchers.IO) { store.upright(page, EDIT_SIZE) } ?: return@launch
            state.scanEdit = ScanEdit(page, image, page.corners, isNew = false)
        }
    }

    fun discard() {
        state.scanPages.clear()
        state.scanImages.clear()
        state.scanExportOpen = false
        state.scanReviewOpen = false
        state.scanExportDone = null
        state.scanExportOptions = state.scanExportOptions.copy(name = ExportNames.defaultName())
        activity.lifecycleScope.launch(storeThread) { store.clear() }
    }

    /** Renders a page at [size] (long side) into state.scanImages, one at a time to keep memory low. */
    fun image(page: ScanPage, size: Int) {
        val key = "${page.key}@$size"
        if (state.scanImages.containsKey(key) || !pending.add(key)) return
        activity.lifecycleScope.launch {
            val bitmap = withContext(renderThread) {
                if (state.scanPages.none { it.key == page.key }) null else runCatching { store.render(page, size) }.getOrNull()
            }
            pending.remove(key)
            if (bitmap == null) return@launch
            if (state.scanPages.none { it.key == page.key }) {
                bitmap.recycle()
                return@launch
            }
            state.scanImages[key] = bitmap
            // Keep only a few big previews in memory.
            val big = state.scanImages.keys.filter { !it.endsWith("@$THUMB") }
            if (big.size > 4) big.filter { it != key }.take(big.size - 4).forEach { state.scanImages.remove(it) }
        }
    }

    fun export(options: ExportOptions, target: ExportTarget) {
        val pages = state.scanPages.toList()
        if (pages.isEmpty() || state.scanExportProgress != null) return
        state.scanExportOptions = options
        state.scanExportDone = null
        state.scanExportProgress = 0f
        activity.lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching {
                    exporter.build(pages, options) { done, total ->
                        activity.runOnUiThread { state.scanExportProgress = done.toFloat() / total }
                    }
                }.getOrElse {
                    null
                }
            }
            if (files == null) {
                state.scanExportProgress = null
                state.message = "Couldn't make the files"
                return@launch
            }
            val what = describe(pages.size, options.format)
            when (target) {
                ExportTarget.SHARE -> {
                    state.scanExportProgress = null
                    runCatching { activity.startActivity(exporter.shareIntent(files, options.format)) }
                        .onFailure { state.message = "No app to share with" }
                }
                ExportTarget.SAVE -> {
                    val folder = withContext(Dispatchers.IO) { runCatching { exporter.saveToPhone(files, options.format) }.getOrNull() }
                    state.scanExportProgress = null
                    if (folder == null) state.message = "Couldn't save" else state.scanExportDone = "Saved $what to $folder"
                }
                ExportTarget.SAVE_TO -> {
                    if (files.size == 1) {
                        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(options.format.mime)
                            .putExtra(Intent.EXTRA_TITLE, files[0].name)
                        pickPlace(intent) { uri ->
                            if (uri == null) {
                                state.scanExportProgress = null
                            } else {
                                finishSaveTo(what) { exporter.saveTo(uri, files[0]) }
                            }
                        }
                    } else {
                        pickPlace(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) { uri ->
                            if (uri == null) {
                                state.scanExportProgress = null
                            } else {
                                finishSaveTo(what) { exporter.saveToFolder(uri, files, options.format) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun finishSaveTo(what: String, write: () -> Unit) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { write() }.isSuccess }
            state.scanExportProgress = null
            if (ok) state.scanExportDone = "Saved $what" else state.message = "Couldn't save there"
        }
    }

    private fun describe(pages: Int, format: ExportFormat): String {
        val p = if (pages == 1) "1 page" else "$pages pages"
        return when (format) {
            ExportFormat.PDF -> "$p as PDF"
            else -> "$p as ${format.label}"
        }
    }

    private fun replace(old: ScanPage, new: ScanPage) {
        val index = state.scanPages.indexOfFirst { it.id == old.id }
        if (index < 0) return
        state.scanPages[index] = new
        dropImages(old.id, keep = new.key)
    }

    private fun dropImages(id: Long, keep: String? = null) {
        state.scanImages.keys.filter { it.startsWith("$id:") && (keep == null || !it.startsWith("$keep@")) }
            .forEach { state.scanImages.remove(it) }
    }

    private fun persist() {
        val pages = state.scanPages.toList()
        activity.lifecycleScope.launch(storeThread) { store.save(pages) }
    }

    companion object {
        const val MAX_PAGES = 60
        const val THUMB = 240
        const val PREVIEW = 1400
        private const val EDIT_SIZE = 1600
    }
}
