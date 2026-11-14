package sh.swifttui.android.host

import java.util.IdentityHashMap
import org.json.JSONObject

/**
 * Manual driver for the [DecodedRowWorkloads] streams. It runs the production
 * poller and decoder session over one workload and, for every record, performs
 * what the host does next: the publication copy `SwiftTUIHostState` makes, the
 * damage plan, and the cell traversal `SwiftTUIRenderer` performs for that
 * plan. JNI and Canvas are outside it.
 *
 * One process is one observation. The same source runs on a desktop JVM and,
 * through `app_process`, on ART. A variant is a [SwiftTUIRowDecoding] token, or
 * `head` for classes that predate the switch.
 *
 * ```
 * corpus
 * calibrate  <workload> <WxH>
 * measure    <workload> <WxH> <variant> <warmupOperations> <samples>
 * interleave <workload> <WxH> <variant,variant,…> <warmupOperations> <blocks> <allocationOperations> <rotation>
 * profile    <workload> <WxH> <variant>
 * retention  <workload> <WxH> <variant> <replicas>
 * ```
 *
 * `measure` times one variant per process. `interleave` runs several sessions
 * in one process and alternates single operations between them, so a host
 * whose load changes from second to second loads every variant alike.
 */
fun main(arguments: Array<String>) {
  println(DecodedRowBenchmark.run(arguments))
}

internal object DecodedRowBenchmark {
  /** The variant of classes that predate the switch: their only session constructor. */
  private const val UNSWITCHED = "head"

  /**
   * A session on the named row-decoding path. Reflective, so this one source
   * also compiles against classes that have no [SwiftTUIRowDecoding].
   */
  private fun session(variant: String): SwiftTUIWebSurfaceSession {
    if (variant == UNSWITCHED) return SwiftTUIWebSurfaceSession()
    val modes = Class.forName("sh.swifttui.android.host.SwiftTUIRowDecoding")
    val token = modes.getMethod("getToken")
    val mode = modes.enumConstants!!.firstOrNull { token.invoke(it) == variant }
      ?: throw IllegalArgumentException("unknown variant $variant")
    return SwiftTUIWebSurfaceSession::class.java.getConstructor(modes).newInstance(mode)
  }

  fun run(arguments: Array<String>): JSONObject {
    val mode = arguments[0]
    if (mode == "corpus") return corpus()
    val name = arguments[1]
    val (width, height) = arguments[2].split('x').map(String::toInt)
    val variant = arguments.getOrElse(3) { UNSWITCHED }
    val workload = DecodedRowWorkloads.build(name, width, height)
    val result = when (mode) {
      "calibrate" -> calibrate(workload)
      "measure" ->
        measure(workload, variant, warmupOperations = arguments[4].toInt(), samples = arguments[5].toInt())
      "interleave" -> interleave(
        workload, variants = variant.split(','), warmupOperations = arguments[4].toInt(),
        blocks = arguments[5].toInt(), allocationOperations = arguments[6].toInt(),
        rotation = arguments[7].toInt()
      )
      "profile" -> profile(workload, variant)
      "retention" -> retention(workload, variant, replicas = arguments[4].toInt())
      else -> throw IllegalArgumentException("unknown mode $mode")
    }
    return result.put("mode", mode).put("workload", workload.id).put("variant", variant)
      .put("engine", Probe.engine)
  }

  private fun corpus(): JSONObject {
    val workloads = JSONObject()
    for ((width, height) in DecodedRowWorkloads.grids) {
      for (name in DecodedRowWorkloads.names) {
        val workload = DecodedRowWorkloads.build(name, width, height)
        workloads.put(
          workload.id,
          JSONObject().put("keyframeBytes", workload.keyframe.bytes.size)
            .put("steps", workload.steps.size)
            .put("stepBytes", workload.steps.sumOf { it.bytes.size.toLong() })
        )
      }
    }
    return JSONObject().put("mode", "corpus").put("engine", Probe.engine)
      .put("corpusSHA256", DecodedRowWorkloads.corpusSHA256()).put("workloads", workloads)
  }

  private fun calibrate(workload: DecodedRowWorkload): JSONObject {
    val host = Host(workload, UNSWITCHED)
    host.start()
    val warmUntil = System.nanoTime() + 1_500_000_000L
    var operations = 0
    do {
      repeat(20) { host.advance() }
      operations += 20
    } while (System.nanoTime() < warmUntil)
    return JSONObject().put("warmupOperations", operations)
  }

  private fun timerResolutionUS(): Double {
    val timerSteps = LongArray(10_000)
    var timerCount = 0
    var priorStamp = System.nanoTime()
    for (index in timerSteps.indices) {
      val next = System.nanoTime()
      if (next > priorStamp) timerSteps[timerCount++] = next - priorStamp
      priorStamp = next
    }
    check(timerCount > 0) { "clock did not advance during calibration" }
    return timerSteps.copyOf(timerCount).sorted()[timerCount / 2].toDouble() / 1000
  }

  /**
   * Several sessions over the same records in one process. After an equal
   * interleaved warmup, each block runs one operation on every session, in an
   * order that rotates from block to block and reverses every full rotation,
   * and times each operation on its own. Allocation is counted afterwards over
   * consecutive operations of one session at a time.
   */
  private fun interleave(
    workload: DecodedRowWorkload,
    variants: List<String>,
    warmupOperations: Int,
    blocks: Int,
    allocationOperations: Int,
    rotation: Int
  ): JSONObject {
    check(warmupOperations > 0 && blocks > 0 && allocationOperations > 0 && rotation >= 0)
    val timerResolutionUS = timerResolutionUS()
    val hosts = variants.map { Host(workload, it) }
    val count = hosts.size
    hosts.forEach { it.start() }
    repeat(warmupOperations) { hosts.forEach { it.advance() } }
    System.gc()
    Thread.sleep(50)

    val cpuNanos = LongArray(count)
    val wallNanos = LongArray(count)
    val latencies = Array(count) { DoubleArray(blocks) }
    for (block in 0 until blocks) {
      val reversed = (block / count) % 2 == 1
      for (slot in 0 until count) {
        val index = if (reversed) (block + rotation + count - slot) % count else (block + rotation + slot) % count
        val host = hosts[index]
        val cpuStart = Probe.threadCpuNanos()
        val wallStart = System.nanoTime()
        host.advance()
        val wall = System.nanoTime() - wallStart
        cpuNanos[index] += Probe.threadCpuNanos() - cpuStart
        wallNanos[index] += wall
        latencies[index][block] = wall.toDouble() / 1000
      }
    }

    val allocated = DoubleArray(count)
    for (slot in 0 until count) {
      val index = (slot + rotation) % count
      val before = Probe.settledAllocatedBytes()
      repeat(allocationOperations) { hosts[index].advance() }
      allocated[index] = (Probe.settledAllocatedBytes() - before).toDouble() / allocationOperations
    }

    // Equal work is the precondition of every ratio: each session consumed the same frames.
    check(hosts.all { it.checksum == hosts[0].checksum }) { "sessions consumed different frames" }
    val results = org.json.JSONArray()
    hosts.forEachIndexed { index, host ->
      latencies[index].sort()
      results.put(
        JSONObject().put("variant", variants[index]).put("slot", index)
          .put("cpuUS", cpuNanos[index].toDouble() / 1000 / blocks)
          .put("wallUS", wallNanos[index].toDouble() / 1000 / blocks)
          .put("medianUS", latencies[index][blocks / 2])
          .put("p95US", latencies[index][(blocks * .95).toInt()])
          .put("allocatedBytesPerOperation", allocated[index])
          .put("cellsClass", host.published!!.cells.javaClass.name)
      )
    }
    return JSONObject().put("hosts", results).put("warmupOperations", warmupOperations)
      .put("blocks", blocks).put("allocationOperations", allocationOperations)
      .put("rotation", rotation).put("checksum", hosts[0].checksum)
      .put("timerResolutionUS", timerResolutionUS)
  }

  private fun measure(
    workload: DecodedRowWorkload,
    variant: String,
    warmupOperations: Int,
    samples: Int
  ): JSONObject {
    check(warmupOperations > 0 && samples > 0)
    val timerResolutionUS = timerResolutionUS()

    val host = Host(workload, variant)
    host.start()
    repeat(warmupOperations) { host.advance() }
    System.gc()
    Thread.sleep(50)

    // Throughput pass: CPU over consecutive operations.
    val cpuBefore = Probe.threadCpuNanos()
    val wallBefore = System.nanoTime()
    repeat(samples) { host.advance() }
    val wallUS = (System.nanoTime() - wallBefore).toDouble() / 1000 / samples
    val cpuUS = (Probe.threadCpuNanos() - cpuBefore).toDouble() / 1000 / samples

    // Allocation pass, untimed, between two settled counter reads.
    val allocatedBefore = Probe.settledAllocatedBytes()
    repeat(samples) { host.advance() }
    val allocatedBytes = (Probe.settledAllocatedBytes() - allocatedBefore).toDouble() / samples

    // Latency pass: each operation timed on its own.
    val timings = DoubleArray(samples)
    repeat(samples) { index ->
      val start = System.nanoTime()
      host.advance()
      timings[index] = (System.nanoTime() - start).toDouble() / 1000
    }
    timings.sort()
    return JSONObject().put("warmupOperations", warmupOperations).put("samples", samples)
      .put("checksum", host.checksum).put("timerResolutionUS", timerResolutionUS)
      .put("cpuUS", cpuUS).put("wallUS", wallUS)
      .put("medianUS", timings[samples / 2]).put("p95US", timings[(samples * .95).toInt()])
      .put("allocatedBytesPerOperation", allocatedBytes)
      .put("cellsClass", host.published!!.cells.javaClass.name)
  }

  /** Untimed: what each record changes and how many cell objects each frame allocates. */
  private fun profile(workload: DecodedRowWorkload, variant: String): JSONObject {
    val host = Host(workload, variant)
    host.start()
    var previous = host.published!!
    val operations = workload.steps.size * 2
    var changedCells = 0L
    var freshCells = 0L
    var linkedCells = 0L
    var replacedRows = 0L
    var recordBytes = 0L
    var fullRepaints = 0L
    repeat(operations) { operation ->
      val record = workload.steps[operation % workload.steps.size]
      host.advance()
      val frame = host.published!!
      val before = HashMap<Long, SwiftTUICell>(previous.cells.size * 2)
      val known = IdentityHashMap<SwiftTUICell, Boolean>(previous.cells.size * 2)
      for (cell in previous.cells) {
        before[cell.y.toLong() shl 32 or cell.x.toLong()] = cell
        known[cell] = true
      }
      var changed = 0
      for (cell in frame.cells) {
        if (before[cell.y.toLong() shl 32 or cell.x.toLong()] != cell) changed += 1
        if (cell !in known) freshCells += 1
        if (cell.hyperlink != null) linkedCells += 1
      }
      check(changed == record.changedCells) {
        "${workload.id} step $operation: decoded $changed changed cells, generator declared ${record.changedCells}"
      }
      changedCells += changed
      replacedRows += record.replacedRows
      recordBytes += record.bytes.size
      if (host.lastPlanWasFullRepaint) fullRepaints += 1
      previous = frame
    }
    return JSONObject().put("operations", operations).put("cycleSteps", workload.steps.size)
      .put("gridCells", previous.cells.size)
      .put("changedCellsPerFrame", changedCells.toDouble() / operations)
      .put("replacedRowsPerFrame", replacedRows.toDouble() / operations)
      .put("recordBytesPerFrame", recordBytes.toDouble() / operations)
      .put("freshCellObjectsPerFrame", freshCells.toDouble() / operations)
      .put("linkedCellsPerFrame", linkedCells.toDouble() / operations)
      .put("fullRepaintFraction", fullRepaints.toDouble() / operations)
      .put("cellsClass", previous.cells.javaClass.name)
      .put("checksum", host.checksum)
  }

  /** Live bytes after GC, per session, in the three ownership states. */
  private fun retention(workload: DecodedRowWorkload, variant: String, replicas: Int): JSONObject {
    val hosts = arrayOfNulls<Host>(replicas)
    val empty = Probe.liveBytes()
    for (index in hosts.indices) {
      val host = Host(workload, variant)
      host.start()
      // One whole cycle, so every row the cycle touches has been replaced.
      repeat(workload.steps.size) { host.advance() }
      hosts[index] = host
    }
    val sessionAndFrame = Probe.liveBytes()
    hosts.forEach { it!!.published = null }
    val sessionOnly = Probe.liveBytes()
    hosts.forEach { it!!.poller.reset() }
    val afterReset = Probe.liveBytes()
    hosts.fill(null)
    val released = Probe.liveBytes()
    return JSONObject().put("replicas", replicas)
      .put("sessionAndFrameBytes", (sessionAndFrame - empty).toDouble() / replicas)
      .put("sessionOnlyBytes", (sessionOnly - empty).toDouble() / replicas)
      .put("afterResetBytes", (afterReset - empty).toDouble() / replicas)
      .put("releasedBytes", (released - empty).toDouble() / replicas)
  }

  /** The production poller and session behind a deterministic copy callback. */
  private class Host(workload: DecodedRowWorkload, variant: String) {
    private val stream = DecodedRowStream(workload)
    private var record = ByteArray(0)
    val poller = SwiftTUIFramePoller(
      session = session(variant),
      copyLatestFrame = { _, output, capacity ->
        if (output != null && capacity >= record.size) record.copyInto(output)
        record.size
      },
      requestResync = { _, _, _ -> 1 }
    )
    var published: SwiftTUIFrame? = null
    var checksum = 0L
    var lastPlanWasFullRepaint = false
    private var lastRenderedGeneration = -1L

    fun start() {
      record = stream.keyframe()
      present()
    }

    fun advance() {
      record = stream.next()
      present()
    }

    private fun present() {
      val result = poller.poll(1L)
      check(result is SwiftTUIFramePollResult.Frame) { "record was not presented: $result" }
      // SwiftTUIHostState publishes a copy that carries the accepted action response.
      val frame = result.value.copy(accessibilityActionResponse = null)
      published = frame
      val plan = SwiftTUIDamagePlan.plan(frame, lastRenderedGeneration, sizeChanged = false)
      lastPlanWasFullRepaint = plan.fullRepaint
      if (plan.fullRepaint) {
        // SwiftTUIRenderer.drawCells visits every cell of the frame's list.
        frame.cells.forEach { cell ->
          if (!cell.isContinuation) checksum += cell.x + cell.spanWidth
        }
      } else {
        // SwiftTUIRenderer.renderGridToCache collects the damaged cells through the row index.
        val damagedCells = ArrayList<SwiftTUICell>()
        SwiftTUIDamagePlan.forEachDamagedCell(frame, plan, damagedCells::add)
        for (cell in damagedCells) checksum += cell.x + cell.spanWidth
      }
      lastRenderedGeneration = frame.consumedGeneration
    }
  }

  /** CPU, allocation and live-heap counters of whichever runtime this is. */
  private object Probe {
    private val isArt = System.getProperty("java.vm.name") == "Dalvik"
    val engine: String =
      if (isArt) "Android ART" else "JVM ${System.getProperty("java.vm.version")}"

    private val threadBean: Any? =
      if (isArt) null
      else Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    private val allocatedMethod =
      if (isArt) null
      else Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
    private val cpuMethod =
      if (isArt) null
      else Class.forName("java.lang.management.ThreadMXBean").getMethod("getCurrentThreadCpuTime")

    /**
     * Bytes allocated so far, read right after a blocking collection. ART
     * debits freed bytes from its live count before it credits them to its
     * freed total, so a read during a concurrent collection undercounts by up
     * to that collection's garbage.
     */
    fun settledAllocatedBytes(): Long {
      System.gc()
      return if (isArt) Art.allocatedBytes() else allocatedMethod!!.invoke(threadBean) as Long
    }

    fun threadCpuNanos(): Long =
      if (isArt) Art.threadCpuNanos() else cpuMethod!!.invoke(threadBean) as Long

    fun liveBytes(): Long {
      var best = Long.MAX_VALUE
      repeat(4) {
        System.gc()
        Thread.sleep(40)
        val runtime = Runtime.getRuntime()
        best = minOf(best, runtime.totalMemory() - runtime.freeMemory())
      }
      return best
    }
  }

  /** Loaded only on ART: a desktop JVM has no `android.os.Debug`. */
  private object Art {
    fun allocatedBytes(): Long =
      android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull()
        ?: error("ART allocation counter unavailable")

    fun threadCpuNanos(): Long = android.os.Debug.threadCpuTimeNanos()
  }
}
