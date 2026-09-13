package sh.swifttui.android.host

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock

/** Manual, device-backed qualification of the real renderer's cell paint loop. */
class ClipQualificationRunner : Instrumentation() {
  override fun onCreate(arguments: Bundle?) {
    super.onCreate(arguments)
    start()
  }

  override fun onStart() {
    try {
      val result = qualify()
      finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nCLIP-QUALIFICATION $result\n") })
    } catch (error: Throwable) {
      finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", error.stackTraceToString()) })
    }
  }

  private class QualificationCanvas(bitmap: Bitmap) : Canvas(bitmap) {
    var clipping = true
    var clips = 0
    override fun save(): Int = if (clipping) super.save() else 1
    override fun restoreToCount(saveCount: Int) { if (clipping) super.restoreToCount(saveCount) }
    override fun clipRect(rect: RectF): Boolean {
      clips++
      return if (clipping) super.clipRect(rect) else true
    }
  }

  private fun qualify(): String {
    val renderer = SwiftTUIRenderer()
    val style = SwiftTUIAndroidStyle(10f, 24f)
    val textPaint = SwiftTUIRenderer::class.java.getDeclaredField("textPaint").apply { isAccessible = true }
      .get(renderer) as Paint
    textPaint.textSize = style.cellHeightPx * 0.78f
    val baseline = (style.cellHeightPx - textPaint.fontMetrics.bottom - textPaint.fontMetrics.top) / 2f
    val draw = SwiftTUIRenderer::class.java.getDeclaredMethod("drawCells", Canvas::class.java,
      List::class.java, SwiftTUITerminalStyle::class.java, SwiftTUIAndroidStyle::class.java, Float::class.javaPrimitiveType)
      .apply { isAccessible = true }
    val cells = (0 until 9600).map { SwiftTUICell(it % 160, it / 160, "W", 1, null, null, null) }
    val bitmap = Bitmap.createBitmap(1600, 1440, Bitmap.Config.ARGB_8888)
    val canvas = QualificationCanvas(bitmap)
    fun paint(values: List<SwiftTUICell>) { draw.invoke(renderer, canvas, values, SwiftTUITerminalStyle.Default, style, baseline) }
    fun pixels(): IntArray = IntArray(bitmap.width * bitmap.height).also {
      bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    fun measure(clipping: Boolean): Double {
      canvas.clipping = clipping
      val samples = ArrayList<Double>()
      repeat(25) { index ->
        bitmap.eraseColor(0xff000000.toInt())
        canvas.clips = 0
        val start = SystemClock.elapsedRealtimeNanos()
        paint(cells)
        if (index >= 5) samples += (SystemClock.elapsedRealtimeNanos() - start) / 1e6
      }
      return samples.sorted()[samples.size / 2]
    }
    try {
      val clippedMs = measure(true)
      val clipped = pixels()
      val unclippedMs = measure(false)
      val unclipped = pixels()
      val differences = clipped.indices.count { clipped[it] != unclipped[it] }
      check(canvas.clips == 9600)
      check(differences > 0) { "the deliberately narrow span must expose clipped ink" }
      canvas.clipping = true
      bitmap.eraseColor(0xff000000.toInt())
      paint(cells)
      val changed = cells.toMutableList()
      changed[4880] = changed[4880].copy(character = "I")
      canvas.drawRect(800f, 720f, 810f, 744f, Paint().apply { color = 0xff000000.toInt() })
      paint(listOf(changed[4880]))
      val incremental = pixels()
      bitmap.eraseColor(0xff000000.toInt())
      paint(changed)
      check(incremental.contentEquals(pixels())) { "partial replacement differs from full paint" }
      return "clippedMs=$clippedMs unclippedMs=$unclippedMs clips=9600 pixelDifferences=$differences incrementalEqualsFull=true"
    } finally {
      bitmap.recycle()
    }
  }
}
