package nexplorer.jobs

import android.content.Context
import android.widget.Toast
import nexplorer.files.Archives
import nexplorer.files.FileOps
import nexplorer.files.Progress
import nexplorer.files.RecycleBin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object FileJobs {
    class Running internal constructor(val title: String) : Progress {
        @Volatile var total = 0L
        @Volatile var done = 0L
            private set
        @Volatile var bytes = true
        /** The item being worked on. */
        @Volatile var detail = ""
        @Volatile internal var cancelled = false

        override fun add(n: Long) {
            if (cancelled) throw Cancelled()
            done += n
        }
    }

    class Cancelled : RuntimeException("Cancelled")

    private class Outcome(val message: String, val after: () -> Unit = {})

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val oneAtATime = Mutex()

    @Volatile
    var current: Running? = null
        private set

    private val _active = MutableStateFlow(0)
    /** Jobs waiting or running. */
    val active: StateFlow<Int> = _active

    private val _started = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val started: SharedFlow<Unit> = _started

    private val _finished = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
    val finished: SharedFlow<Boolean> = _finished

    fun cancelCurrent() {
        current?.cancelled = true
    }

    private fun run(ctx: Context, title: String, makesFiles: Boolean, work: (Running) -> Outcome) {
        val app = ctx.applicationContext
        _active.update { it + 1 }
        _started.tryEmit(Unit)
        FileJobService.start(app)
        scope.launch {
            val outcome = oneAtATime.withLock {
                val r = Running(title)
                current = r
                try {
                    work(r)
                } catch (t: Throwable) {
                    Outcome("${title.substringBefore(' ')} didn't work: ${t.message ?: "error"}")
                } finally {
                    current = null
                }
            }
            _active.update { it - 1 }
            withContext(Dispatchers.Main) {
                Toast.makeText(app, outcome.message, Toast.LENGTH_SHORT).show()
                outcome.after()
            }
            _finished.tryEmit(makesFiles)
        }
    }

    private fun items(n: Int) = if (n == 1) "1 item" else "$n items"

    private fun <T> eachOf(list: List<T>, r: Running, name: (T) -> String, each: (T) -> Unit): Triple<Int, String?, Boolean> {
        var ok = 0
        var problem: String? = null
        for (item in list) {
            if (r.cancelled) return Triple(ok, problem, true)
            r.detail = name(item)
            try {
                each(item)
                ok++
            } catch (_: Cancelled) {
                return Triple(ok, problem, true)
            } catch (t: Throwable) {
                if (problem == null) problem = "${name(item)}: ${t.message ?: "didn't work"}"
            }
        }
        return Triple(ok, problem, false)
    }

    private fun summary(done: String, ok: Int, of: Int, problem: String?, cancelled: Boolean, stopped: String, suffix: String = "") = when {
        cancelled -> "$stopped stopped: $ok of ${items(of)} ${done.lowercase()}$suffix"
        problem != null -> "$done $ok of ${items(of)}$suffix. $problem"
        else -> "$done ${items(of)}$suffix"
    }

    fun transfer(ctx: Context, items: List<Pair<File, FileOps.OnConflict>>, dest: File, move: Boolean, onMoved: (File, File) -> Unit) {
        val verb = if (move) "Moving" else "Copying"
        run(ctx, "$verb ${items(items.size)}", makesFiles = true) { r ->
            r.total = items.sumOf { FileOps.sizeOf(it.first) }
            val moved = ArrayList<Pair<File, File>>()
            val (ok, problem, cancelled) = eachOf(items, r, { it.first.name }) { (f, choice) ->
                if (move) moved += f to FileOps.moveTo(f, dest, choice, r) else FileOps.copyTo(f, dest, choice, r)
            }
            Outcome(
                summary(if (move) "Moved" else "Copied", ok, items.size, problem, cancelled, verb),
                after = { moved.forEach { (from, to) -> onMoved(from, to) } },
            )
        }
    }

    fun delete(ctx: Context, targets: List<File>, toBin: Boolean, roots: List<File>, onDeleted: (File) -> Unit) {
        val title = if (toBin) "Moving ${items(targets.size)} to the recycle bin" else "Deleting ${items(targets.size)}"
        run(ctx, title, makesFiles = false) { r ->
            r.bytes = toBin
            r.total = if (toBin) targets.sumOf { FileOps.sizeOf(it) } else targets.sumOf { FileOps.countAll(it).toLong() }
            val gone = ArrayList<File>()
            val (ok, problem, cancelled) = eachOf(targets, r, { it.name }) { f ->
                if (toBin) RecycleBin.put(f, roots, r) else check(FileOps.deleteFromDevice(f, r)) { "Couldn't delete everything" }
                gone += f
            }
            val message = if (toBin) {
                summary("Moved", ok, targets.size, problem, cancelled, "Deleting", suffix = " to the recycle bin")
            } else {
                summary("Deleted", ok, targets.size, problem, cancelled, "Deleting")
            }
            Outcome(message, after = { gone.forEach(onDeleted) })
        }
    }

    /** Zips [targets] into [out]. */
    fun zip(ctx: Context, targets: List<File>, out: File) {
        run(ctx, "Compressing ${items(targets.size)}", makesFiles = true) { r ->
            r.total = targets.sumOf { FileOps.sizeOf(it) }
            r.detail = out.name
            try {
                Archives.zip(targets, out, r)
                Outcome("Saved ${out.name}")
            } catch (t: Throwable) {
                out.delete()
                Outcome(if (t is Cancelled) "Compressing stopped" else "Couldn't compress: ${t.message ?: "error"}")
            }
        }
    }

    fun extract(ctx: Context, archive: File, into: File?, totalBytes: Long, onExtracted: (File) -> Unit = {}) {
        run(ctx, "Extracting ${archive.name}", makesFiles = true) { r ->
            r.total = totalBytes
            try {
                val folder = Archives.extract(archive, into, r) { n -> r.detail = if (n == 1) "1 file" else "$n files" }
                Outcome("Extracted into ${folder.name}", after = { onExtracted(folder) })
            } catch (t: Throwable) {
                Outcome(if (t is Cancelled) "Extracting stopped" else "Couldn't extract this archive")
            }
        }
    }

    /** Puts [binned] back where they were. */
    fun restore(ctx: Context, binned: List<RecycleBin.Item>, onRestored: () -> Unit = {}) {
        run(ctx, "Recovering ${items(binned.size)}", makesFiles = true) { r ->
            r.total = binned.sumOf { FileOps.sizeOf(it.file) }
            val (ok, problem, cancelled) = eachOf(binned, r, { it.file.name }) { RecycleBin.restore(it, r) }
            Outcome(summary("Recovered", ok, binned.size, problem, cancelled, "Recovering"), after = onRestored)
        }
    }

    fun deleteForever(ctx: Context, binned: List<RecycleBin.Item>, title: String? = null, onDone: () -> Unit = {}) {
        run(ctx, title ?: "Deleting ${items(binned.size)}", makesFiles = false) { r ->
            r.bytes = false
            r.total = binned.sumOf { RecycleBin.count(it).toLong() }
            val (ok, problem, cancelled) = eachOf(binned, r, { it.file.name }) {
                check(RecycleBin.delete(it, r)) { "Couldn't delete everything" }
            }
            val message = if (title != null && problem == null && !cancelled) "The recycle bin is empty"
            else summary("Deleted", ok, binned.size, problem, cancelled, "Deleting")
            Outcome(message, after = onDone)
        }
    }
}
