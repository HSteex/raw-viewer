package com.hsteex.rawviewer

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.hsteex.rawviewer.data.Filter
import com.hsteex.rawviewer.data.Flag
import com.hsteex.rawviewer.data.GridItem
import com.hsteex.rawviewer.data.ImportFormat
import com.hsteex.rawviewer.data.ImportWorker
import com.hsteex.rawviewer.data.Library
import com.hsteex.rawviewer.data.PhotoMeta
import com.hsteex.rawviewer.data.Rating
import com.hsteex.rawviewer.data.RootEntry
import com.hsteex.rawviewer.data.Scanner
import com.hsteex.rawviewer.data.Shot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface Screen {
    data object Home : Screen
    data object Browser : Screen
    data class Viewer(val keys: List<String>, val start: Int) : Screen
    data class Compare(val a: String, val b: String) : Screen
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val files: Int) : ScanState
    data class Done(val shots: Int, val ms: Long) : ScanState
    data class Error(val message: String) : ScanState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appx = app as App
    val repo = appx.images
    private val db = appx.db
    val prefs = appx.prefs

    private val backStack = ArrayDeque<Screen>()
    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    val roots = MutableStateFlow<List<RootEntry>>(emptyList())
    val root = MutableStateFlow<RootEntry?>(null)
    val scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val shots = MutableStateFlow<List<Shot>>(emptyList())
    val metas = MutableStateFlow<Map<String, PhotoMeta>>(emptyMap())
    val ratings = MutableStateFlow<Map<String, Rating>>(emptyMap())
    val filter = MutableStateFlow(Filter())
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val indexing = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Ultima modifica ai voti, per "Annulla". */
    private var undoSnapshot: Map<String, Rating>? = null
    val canUndo = MutableStateFlow(false)

    private var scanJob: Job? = null
    private var indexJob: Job? = null

    val shotByKey: StateFlow<Map<String, Shot>> = shots.map { l -> l.associateBy { it.key } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val folders: StateFlow<List<String>> = shots.map { l -> l.map { it.relDir }.distinct().sorted() }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filtered: StateFlow<List<Shot>> = combine(shots, metas, ratings, filter) { s, m, r, f ->
        Library.apply(s, m, r, f)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val gridItems: StateFlow<List<GridItem>> = combine(filtered, metas, filter, prefs.burstGapMs) { list, m, f, gap ->
        Library.bursts(list, m, gap, f.groupBursts)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val importWork: StateFlow<WorkInfo?> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(ImportWorker.UNIQUE)
        .map { list -> list.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: list.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            ratings.value = db.loadRatings()
            roots.value = db.listRoots()
        }
    }

    // ------------------------------------------------------------ navigazione

    fun navigate(s: Screen) {
        backStack.addLast(_screen.value)
        _screen.value = s
    }

    /** @return false se siamo già alla schermata iniziale */
    fun back(): Boolean {
        if (_screen.value == Screen.Browser && selection.value.isNotEmpty()) {
            selection.value = emptySet()
            return true
        }
        val prev = backStack.removeLastOrNull() ?: return false
        _screen.value = prev
        return true
    }

    // ------------------------------------------------------------ cartelle

    fun openTree(uri: Uri) {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val name = queryTreeName(uri)
            db.upsertRoot(uri, name)
            roots.value = db.listRoots()
            val entry = roots.value.firstOrNull { it.uri == uri } ?: RootEntry(uri, name, System.currentTimeMillis())
            withContext(Dispatchers.Main) { openRoot(entry) }
        }
    }

    private fun queryTreeName(tree: Uri): String = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        getApplication<Application>().contentResolver.query(
            doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: docId.substringAfterLast('/').substringAfter(':')
    }.getOrDefault("Cartella")

    fun removeRoot(r: RootEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            db.deleteRoot(r.uri)
            runCatching {
                getApplication<Application>().contentResolver
                    .releasePersistableUriPermission(r.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            roots.value = db.listRoots()
        }
    }

    fun openRoot(r: RootEntry) {
        if (root.value?.uri != r.uri || shots.value.isEmpty()) {
            root.value = r
            filter.value = filter.value.copy(folder = null)
            selection.value = emptySet()
            startScan()
        }
        viewModelScope.launch(Dispatchers.IO) { db.upsertRoot(r.uri, r.name) }
        navigate(Screen.Browser)
    }

    fun startScan() {
        val r = root.value ?: return
        scanJob?.cancel()
        indexJob?.cancel()
        scanJob = viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val counter = AtomicInteger(0)
            scan.value = ScanState.Scanning(0)
            val ticker = launch {
                while (isActive) {
                    scan.value = ScanState.Scanning(counter.get()); delay(120)
                }
            }
            try {
                val files = Scanner.scan(getApplication<Application>(), r.uri, counter)
                val grouped = withContext(Dispatchers.Default) { Scanner.group(files, r.uri) }
                val known = withContext(Dispatchers.IO) { repo.preloadInfos(grouped) }
                ticker.cancel()
                metas.value = known
                shots.value = grouped
                scan.value = ScanState.Done(grouped.size, System.currentTimeMillis() - t0)
                startIndexing(grouped)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ticker.cancel()
                scan.value = ScanState.Error(e.message ?: "Errore di lettura")
            }
        }
    }

    /** In background: metadati di tutti gli scatti, poi tutte le miniature su disco. */
    private fun startIndexing(list: List<Shot>) {
        indexJob?.cancel()
        indexJob = viewModelScope.launch(Dispatchers.Default) {
            val todo = list.filter { !repo.hasMeta(it) }
            val buffer = ConcurrentHashMap<String, PhotoMeta>()
            val done = AtomicInteger(0)
            val total = todo.size
            if (total > 0) {
                indexing.value = 0 to total
                val flusher = launch {
                    while (isActive) {
                        delay(400)
                        flushMetas(buffer)
                        indexing.value = done.get() to total
                    }
                }
                val sem = Semaphore(3)
                val jobs = todo.map { s ->
                    launch {
                        sem.withPermit {
                            repo.indexShot(s)?.let { buffer[s.key] = it }
                            done.incrementAndGet()
                        }
                    }
                }
                jobs.forEach { it.join() }
                flusher.cancel()
                flushMetas(buffer)
            }
            indexing.value = null
            // miniature su disco, nell'ordine in cui le vedrai
            val sem = Semaphore(2)
            filtered.value.map { s -> launch { sem.withPermit { runCatching { repo.ensureGridOnDisk(s) } } } }
                .forEach { it.join() }
        }
    }

    private fun flushMetas(buffer: ConcurrentHashMap<String, PhotoMeta>) {
        if (buffer.isEmpty()) return
        val batch = HashMap(buffer)
        batch.keys.forEach { buffer.remove(it) }
        metas.value = metas.value + batch
    }

    // ------------------------------------------------------------ voti

    fun rating(key: String): Rating = ratings.value[key] ?: Rating()

    private fun applyRatings(keys: Collection<String>, transform: (Rating) -> Rating) {
        if (keys.isEmpty()) return
        val current = ratings.value
        val before = HashMap<String, Rating>()
        val changes = HashMap<String, Rating>()
        for (k in keys) {
            val old = current[k] ?: Rating()
            before[k] = old
            changes[k] = transform(old)
        }
        undoSnapshot = before
        canUndo.value = true
        val next = HashMap(current)
        for ((k, r) in changes) if (r.isEmpty) next.remove(k) else next[k] = r
        ratings.value = next
        viewModelScope.launch(Dispatchers.IO) { db.saveRatings(changes) }
    }

    /** Su un solo scatto: ripremere lo stesso tasto toglie il flag. */
    fun toggleFlag(key: String, flag: Flag) =
        applyRatings(listOf(key)) { it.copy(flag = if (it.flag == flag) Flag.NONE else flag) }

    fun setFlag(keys: Collection<String>, flag: Flag) = applyRatings(keys) { it.copy(flag = flag) }

    fun toggleStars(key: String, stars: Int) =
        applyRatings(listOf(key)) { it.copy(stars = if (it.stars == stars) 0 else stars) }

    fun setStars(keys: Collection<String>, stars: Int) = applyRatings(keys) { it.copy(stars = stars) }

    fun undo() {
        val snap = undoSnapshot ?: return
        undoSnapshot = null
        canUndo.value = false
        val next = HashMap(ratings.value)
        for ((k, r) in snap) if (r.isEmpty) next.remove(k) else next[k] = r
        ratings.value = next
        viewModelScope.launch(Dispatchers.IO) { db.saveRatings(snap) }
    }

    // ------------------------------------------------------------ selezione

    fun toggleSelect(keys: List<String>) {
        val cur = selection.value
        selection.value = if (keys.all { it in cur }) cur - keys.toSet() else cur + keys
    }

    fun selectAllFiltered() {
        selection.value = filtered.value.map { it.key }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun selectedShots(): List<Shot> {
        val sel = selection.value
        return filtered.value.filter { it.key in sel }
    }

    fun pickedShots(): List<Shot> {
        val r = ratings.value
        return shots.value.filter { r[it.key]?.flag == Flag.PICK }
            .sortedBy { Library.time(it, metas.value) }
    }

    // ------------------------------------------------------------ azioni

    fun openViewer(keys: List<String>, start: Int) {
        if (keys.isEmpty()) return
        navigate(Screen.Viewer(keys, start.coerceIn(0, keys.lastIndex)))
    }

    fun openCompare(a: String, b: String) = navigate(Screen.Compare(a, b))

    fun startImport(list: List<Shot>, format: ImportFormat): Int {
        prefs.setImportFormat(format)
        val m = metas.value
        return ImportWorker.enqueue(getApplication<Application>(), list, format) { Library.time(it, m) }
    }

    fun cancelImport() {
        WorkManager.getInstance(getApplication<Application>()).cancelUniqueWork(ImportWorker.UNIQUE)
    }

    fun shareIntent(list: List<Shot>, preferRaw: Boolean = false): Intent? {
        val uris = list.mapNotNull { s -> if (preferRaw) s.raw?.uri ?: s.jpeg?.uri else s.jpeg?.uri ?: s.raw?.uri }
        if (uris.isEmpty()) return null
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val allJpeg = list.all { if (preferRaw) it.raw == null else it.jpeg != null }
        intent.type = if (allJpeg) "image/jpeg" else "image/*"
        val clip = ClipData.newRawUri(null, uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(intent, "Condividi")
    }
}
