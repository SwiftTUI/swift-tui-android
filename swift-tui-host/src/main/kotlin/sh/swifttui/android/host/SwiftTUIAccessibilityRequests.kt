package sh.swifttui.android.host

/** Only a response to an outstanding request in this session may reach the UI. */
internal class SwiftTUIAccessibilityRequests(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
  private data class Pending(val target: String, val expires: Long)
  private val pending = linkedMapOf<Long, Pending>()
  private var nextID = 0L
  private var epoch: Long? = null
  private var hasObservedSession = false

  fun issue(target: String): Long? {
    prune()
    if (nextID == Long.MAX_VALUE) return null
    val id = ++nextID
    if (pending.size >= 256) pending.remove(pending.keys.first())
    pending[id] = Pending(target, now() + 30_000)
    return id
  }

  fun accept(response: SwiftTUIAccessibilityActionResponse?): SwiftTUIAccessibilityActionResponse? {
    prune()
    response ?: return null
    val id = normalizeWireUInt64(response.requestID)?.toLongOrNull() ?: return null
    if (pending[id]?.target != response.target) return null
    pending.remove(id)
    return response.copy(requestID = id.toString())
  }

  fun cancel(id: Long) { pending.remove(id) }
  fun reset() { pending.clear(); epoch = null; hasObservedSession = false }
  fun observeEpoch(value: Long?) {
    if (hasObservedSession && epoch != value) reset()
    epoch = value
    hasObservedSession = true
  }
  fun retainTargets(targets: Set<String>) { pending.entries.removeAll { it.value.target !in targets }; prune() }
  private fun prune() { val time = now(); pending.entries.removeAll { it.value.expires <= time } }
}
