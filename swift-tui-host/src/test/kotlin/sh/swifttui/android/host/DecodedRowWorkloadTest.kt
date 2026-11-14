package sh.swifttui.android.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the measurement streams honest: fixed bytes, presentable records, declared shapes. */
class DecodedRowWorkloadTest {
  private fun eachWorkload(action: (DecodedRowWorkload) -> Unit) {
    for ((width, height) in DecodedRowWorkloads.grids) {
      for (name in DecodedRowWorkloads.names) action(DecodedRowWorkloads.build(name, width, height))
    }
  }

  private class Presenter(workload: DecodedRowWorkload) {
    private val stream = DecodedRowStream(workload)
    private var record = ByteArray(0)
    private val poller = SwiftTUIFramePoller(
      session = SwiftTUIWebSurfaceSession(),
      copyLatestFrame = { _, output, capacity ->
        if (output != null && capacity >= record.size) record.copyInto(output)
        record.size
      },
      requestResync = { _, _, _ -> error("a workload record requested repair") }
    )

    fun keyframe(): SwiftTUIFrame = present(stream.keyframe())
    fun next(): SwiftTUIFrame = present(stream.next())

    private fun present(bytes: ByteArray): SwiftTUIFrame {
      record = bytes
      return (poller.poll(1L) as SwiftTUIFramePollResult.Frame).value
    }
  }

  @Test
  fun corpusBytesAreFixed() {
    assertEquals(
      "c6bd33f892d56104d8ccacc01bc3fd1953d54a83dd0c5b7c5b75711cec0b7f1c",
      DecodedRowWorkloads.corpusSHA256()
    )
  }

  @Test
  fun everyRecordIsPresentedAndChangesExactlyWhatItDeclares() {
    eachWorkload { workload ->
      // The profile pass fails on any record whose decoded change count differs.
      val profile = DecodedRowBenchmark.run(
        arrayOf("profile", workload.name, "${workload.width}x${workload.height}")
      )
      assertEquals(workload.steps.size * 2, profile.getInt("operations"))
    }
  }

  @Test
  fun everyStepCycleClosesOnItsKeyframeAndCoversTheGrid() {
    eachWorkload { workload ->
      val presenter = Presenter(workload)
      val keyframe = presenter.keyframe()
      assertEquals(workload.width, keyframe.gridWidth)
      assertEquals(workload.height, keyframe.gridHeight)
      for (row in 0 until workload.height) {
        assertEquals(
          "${workload.id} row $row", workload.width,
          keyframe.cells.filter { it.y == row }.sumOf { it.spanWidth }
        )
      }
      var frame = keyframe
      // Two cycles: the second replays every delta against a baseline the first produced.
      repeat(2) {
        repeat(workload.steps.size) { frame = presenter.next() }
        assertEquals(workload.id, keyframe.cells, frame.cells)
      }
      assertEquals(keyframe.consumedGeneration + workload.steps.size * 2, frame.consumedGeneration)
    }
  }

  @Test
  fun workloadsHaveTheirDefinedShapes() {
    eachWorkload { workload ->
      val gridCells = Presenter(workload).keyframe().cells.size
      for (step in workload.steps) {
        when (workload.name) {
          "one-cell", "image-one-cell", "linked-one-cell" -> {
            assertEquals(1, step.replacedRows)
            assertEquals(1, step.changedCells)
          }
          "one-row" -> {
            assertEquals(1, step.replacedRows)
            assertTrue(step.changedCells in workload.width / 2..workload.width)
          }
          "scroll" -> {
            assertEquals(workload.height - 2, step.replacedRows)
            assertTrue(step.changedCells > (workload.height - 2) * workload.width / 2)
          }
          "all-rows", "full-keyframe" -> {
            assertEquals(workload.height, step.replacedRows)
            assertEquals(gridCells, step.changedCells)
          }
          else -> error("unclassified workload ${workload.name}")
        }
      }
      val expectedSteps = when (workload.name) {
        "one-row" -> workload.height * 2
        "scroll", "one-cell", "image-one-cell", "linked-one-cell" -> 64
        else -> 2
      }
      assertEquals(workload.id, expectedSteps, workload.steps.size)
    }
  }
}
