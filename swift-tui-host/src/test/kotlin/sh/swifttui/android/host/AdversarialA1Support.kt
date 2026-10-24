package sh.swifttui.android.host

import org.json.JSONObject

internal fun a1Cell(x: Int = 0, y: Int = 0, span: Int = 1, continuation: Int? = null) =
  SwiftTUICell(x, y, "A", span, continuation, null, null)

internal fun a1Image(payload: String? = null) = SwiftTUIImageAttachment(
  "image", SwiftTUIRect(0, 0, 1, 1), SwiftTUIRect(0, 0, 1, 1),
  "data", null, payload, null, null, null, false, "stretch"
)

internal fun a1Frame(
  sequence: Long = 3,
  generation: Long = 3,
  cells: List<SwiftTUICell> = listOf(a1Cell()),
  images: List<SwiftTUIImageAttachment> = emptyList(),
  dirty: List<Int> = emptyList(),
  damage: List<SwiftTUITextDamageRow> = emptyList(),
  fullText: Boolean = false,
  fullGraphics: Boolean = false
) = SwiftTUIFrame(
  sequence, generation, 8, 2, null, null, SwiftTUITerminalStyle.Default,
  cells, images, null, SwiftTUIFocusPresentation.None, emptyList(), emptyList(),
  emptyList(), dirty, damage, fullText, fullGraphics
)

internal fun a1Full(sequence: Int = 1): JSONObject = JSONObject(
  """{"version":2,"sequence":$sequence,"width":2,"height":1,
  "styles":[null],"rows":[[[0,"A",1,0],[1,"B",1,0]]]}"""
)

internal fun a1Delta(): JSONObject = JSONObject(
  """{"version":3,"encoding":"delta","sequence":2,"width":2,"height":1,
  "styles":[null],"deltaRows":[[0,[[0,"Z",1,0]]]]}"""
)

internal fun a1Record(json: JSONObject): String = SwiftTUIWebSurfaceSession.RECORD_PREFIX + json + "\n"

internal fun a1Stamp(json: JSONObject, epoch: Int, gen: Int, baseline: Int? = null): JSONObject {
  json.put("epoch", epoch).put("gen", gen)
  if (baseline != null) json.put("baselineGen", baseline)
  return json
}

/** The ABI callback is synchronous; each deliberate poll consumes at most one queued record. */
internal class A1Native(private val growFirstCopy: Boolean = false) {
  private val records = ArrayDeque<ByteArray>()
  val requests = mutableListOf<JSONObject>()
  var copyCalls = 0
  private var hasGrown = false
  fun enqueue(record: JSONObject) { records.addLast(a1Record(record).encodeToByteArray()) }
  fun enqueueRaw(record: String) { records.addLast(record.encodeToByteArray()) }
  fun makePoller() = SwiftTUIFramePoller(
    SwiftTUIWebSurfaceSession(),
    copyLatestFrame = { _, buffer, capacity ->
      val next = records.firstOrNull()
      when {
        next == null -> 0
        buffer == null -> if (growFirstCopy && !hasGrown) next.size - 1 else next.size
        else -> {
          copyCalls++
          if (capacity < next.size) {
            hasGrown = true
            next.size
          } else {
            next.copyInto(buffer)
            records.removeFirst()
            next.size
          }
        }
      }
    },
    requestResync = { _, bytes, length ->
      requests += JSONObject(bytes.decodeToString(0, length))
      0 // Old-host unsupported return remains an outstanding request.
    }
  )
}
