package nexplorer.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class SharedFile(val id: Int, val file: File, val owner: String)

class Device(val token: String, val name: String, val address: String)

enum class TransferState { Waiting, Rejected, Approved, Downloading, Done, Failed }

class Transfer(val name: String, val from: String, val size: Long) {
    @Volatile var state = TransferState.Waiting
    @Volatile var received = 0L
    @Volatile var file: File? = null
}

class Offer(val id: String, val device: Device, val files: List<Transfer>) {
    @Volatile var answered = false
    val size: Long get() = files.sumOf { it.size }
}

sealed interface JoinResult {
    class Joined(val device: Device) : JoinResult
    class Refused(val message: String, val locked: Boolean = false) : JoinResult
}

class ShareSession(val hostName: String, private val random: SecureRandom = SecureRandom()) {
    @Volatile var pin: String = newDigits()
        private set
    val key: String = token(16)

    private val lock = Any()
    private var nextId = 1
    private val files = LinkedHashMap<Int, SharedFile>()
    private val devices = LinkedHashMap<String, Device>()
    private val wrongByAddress = HashMap<String, Int>()
    private var wrongTotal = 0
    private val offers = LinkedHashMap<String, Offer>()
    private val transfers = ArrayList<Transfer>()
    private val trusted = HashSet<String>()

    @Volatile var onOffer: ((Offer) -> Unit)? = null

    @Volatile var pinOn = true
        private set

    @Volatile private var last = System.currentTimeMillis()
    private val busy = AtomicInteger()

    val lastActivity: Long get() = if (busy.get() > 0) System.currentTimeMillis() else last

    private fun newDigits() = (0 until 6).joinToString("") { random.nextInt(10).toString() }

    fun newPin() = synchronized(lock) {
        pin = newDigits()
        wrongTotal = 0
        pinOn = true
        changed()
    }

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    private fun touch() { last = System.currentTimeMillis() }

    class Sending(val total: Long) {
        @Volatile var sent = 0L
    }

    private val sending = java.util.Collections.synchronizedSet(HashSet<Sending>())

    fun <T> sendingOut(total: Long, block: (Sending) -> T): T {
        val s = Sending(total)
        sending += s
        try {
            return block(s)
        } finally {
            sending -= s
        }
    }

    class Moving(val sending: Int, val receiving: Int, val done: Long, val total: Long)

    fun moving(): Moving? {
        val out = synchronized(sending) { sending.toList() }
        val incoming = synchronized(lock) { transfers.filter { it.state == TransferState.Downloading } }
        if (out.isEmpty() && incoming.isEmpty()) return null
        return Moving(
            out.size, incoming.size,
            out.sumOf { it.sent } + incoming.sumOf { it.received },
            out.sumOf { it.total } + incoming.sumOf { it.size },
        )
    }

    fun <T> transferring(block: () -> T): T {
        busy.incrementAndGet()
        touch()
        try {
            return block()
        } finally {
            touch()
            busy.decrementAndGet()
        }
    }

    internal fun changed() {
        _changes.value++
    }

    fun join(name: String, pin: String?, key: String?, address: String): JoinResult = synchronized(lock) {
        if ((wrongByAddress[address] ?: 0) >= WRONG_PER_ADDRESS) return JoinResult.Refused(LOCKED, locked = true)
        val ok = when {
            !key.isNullOrEmpty() -> same(key, this.key)
            !pin.isNullOrEmpty() -> pinOn && same(pin, this.pin)
            else -> return JoinResult.Refused("Type the PIN shown on the phone")
        }
        if (!ok) {
            val n = (wrongByAddress[address] ?: 0) + 1
            wrongByAddress[address] = n
            if (++wrongTotal >= WRONG_TOTAL) pinOn = false
            changed()
            return when {
                n >= WRONG_PER_ADDRESS -> JoinResult.Refused(LOCKED, locked = true)
                !key.isNullOrEmpty() -> JoinResult.Refused("This QR code is from an earlier share. Scan the new one, or type the PIN.")
                !pinOn -> JoinResult.Refused("Joining by PIN is turned off after too many wrong tries. Scan the QR code on the phone.", locked = true)
                else -> JoinResult.Refused("Wrong PIN")
            }
        }
        val clean = ShareNames.clean(name) ?: return JoinResult.Refused("Type a name for this device")
        val unique = ShareNames.unique(clean, devices.values.map { it.name } + hostName)
        val device = Device(token(32), unique, address)
        devices[device.token] = device
        touch()
        changed()
        JoinResult.Joined(device)
    }

    fun device(token: String?): Device? = token?.let { synchronized(lock) { devices[it] } }

    fun devices(): List<Device> = synchronized(lock) { devices.values.toList() }

    fun remove(device: Device) = synchronized(lock) {
        if (devices.remove(device.token) == null) return@synchronized
        trusted -= device.token
        offers.values.filter { it.device == device }.forEach { o ->
            o.answered = true
            o.files.filter { it.state == TransferState.Waiting || it.state == TransferState.Approved }
                .forEach { it.state = TransferState.Failed }
        }
        changed()
    }

    fun offer(device: Device, items: List<Pair<String, Long>>): Offer? {
        val o = synchronized(lock) {
            if (offers.values.any { it.device.token == device.token && !it.answered }) return null
            val o = Offer(token(16), device, items.map { (name, size) -> Transfer(ShareNames.fileName(name), device.name, size) })
            offers[o.id] = o
            transfers += o.files
            if (device.token in trusted) {
                o.answered = true
                o.files.forEach { it.state = TransferState.Approved }
            }
            changed()
            o
        }
        if (!o.answered) onOffer?.invoke(o)
        return o
    }

    fun offer(id: String?): Offer? = id?.let { synchronized(lock) { offers[it] } }

    fun pending(): List<Offer> = synchronized(lock) { offers.values.filter { !it.answered } }

    fun answer(o: Offer, approve: Boolean, trustDevice: Boolean = false) = synchronized(lock) {
        if (o.answered) return@synchronized
        if (approve && trustDevice && o.device.token in devices) trusted += o.device.token
        o.answered = true
        o.files.forEach { it.state = if (approve) TransferState.Approved else TransferState.Rejected }
        changed()
    }

    fun transfers(): List<Transfer> = synchronized(lock) { transfers.reversed() }

    fun share(picked: List<File>) = synchronized(lock) {
        val have = files.values.filter { it.owner == hostName }.map { it.file.path }.toSet()
        picked.filter { it.isFile && it.path !in have }.forEach { add(it, hostName) }
        changed()
    }

    fun received(file: File, owner: String) = synchronized(lock) {
        add(file, owner)
        changed()
    }

    private fun add(file: File, owner: String) {
        val id = nextId++
        files[id] = SharedFile(id, file, owner)
    }

    fun unshare(id: Int) = synchronized(lock) {
        if (files.remove(id) != null) changed()
    }

    fun file(id: Int): SharedFile? = synchronized(lock) { files[id] }?.takeIf { it.file.isFile }

    fun folders(): List<Pair<String, List<SharedFile>>> {
        val all = synchronized(lock) { files.values.toList() }.filter { it.file.isFile }
        val order = listOf(hostName) + synchronized(lock) { devices.values.map { it.name } }
        return all.groupBy { it.owner }.toList().sortedBy { (owner, _) -> order.indexOf(owner).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    private fun token(bytes: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val WRONG_PER_ADDRESS = 5
        const val WRONG_TOTAL = 20
        private const val LOCKED = "Too many wrong tries from this device. Scan the QR code on the phone, or ask for sharing to be started again."
    }
}

object ShareNames {
    private const val MAX = 40

    fun clean(raw: String, max: Int = MAX): String? {
        val kept = raw.replace(Regex("[\\s\\p{Cntrl}]+"), " ")
            .filter { it !in "\\/:*?\"<>|" }
            .trim().trim('.').trim()
        val cut = if (kept.length > max) kept.take(max).trim() else kept
        return cut.takeIf { it.isNotEmpty() }
    }

    fun unique(name: String, taken: Collection<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in lower) return name
        var n = 2
        while ("$name ($n)".lowercase() in lower) n++
        return "$name ($n)"
    }

    fun fileName(raw: String): String = clean(raw.substringAfterLast('/').substringAfterLast('\\'), 120) ?: "file"
}
