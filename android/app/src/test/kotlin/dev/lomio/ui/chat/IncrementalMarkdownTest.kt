package dev.lomio.ui.chat

import java.io.File
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A long, varied reply like a model would stream: paragraphs, lists, a table, code fences. */
internal fun sampleReply(sections: Int): String = buildString {
  for (i in 1..sections) {
    append("## Step $i\n\n")
    append("This is paragraph $i with **bold**, `inline code` and a [link](https://example.com/$i). ")
    append("It keeps going for a while so the block is a realistic size for a chat reply.\n\n")
    append("- first point $i\n- second point $i\n\n")
    if (i % 3 == 0) append("| a | b |\n|---|---|\n| $i | ${i * 2} |\n\n")
    if (i % 4 == 0) {
      append("```kotlin\n")
      repeat(12) { append("val x$it = listOf(1, 2, 3).map { v -> v * $it } // line $it\n") }
      append("```\n\n")
    }
  }
}

/** Splits [text] into token-sized chunks (3–6 chars), like llama.cpp's streamed pieces. */
internal fun chunks(text: String): List<String> {
  val out = ArrayList<String>()
  var i = 0
  var n = 0
  while (i < text.length) {
    val len = 3 + (n++ % 4)
    out += text.substring(i, minOf(text.length, i + len))
    i += len
  }
  return out
}

class IncrementalMarkdownTest {
  private fun shape(nodes: List<org.intellij.markdown.ast.ASTNode>) =
    nodes.map { Triple(it.type.name, it.startOffset, it.endOffset) }

  @Test
  fun streamedParseMatchesOneShotParse() {
    val text = sampleReply(12)
    val md = IncrementalMarkdown()
    var acc = ""
    var snapshot = md.update("")
    for (c in chunks(text)) {
      acc += c
      snapshot = md.update(acc)
    }
    val oneShot = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text).children
    assertEquals(shape(oneShot), shape(snapshot.blocks.map { it.node }))
    assertEquals(0, md.resets)
    // Every character went to the parser exactly once.
    assertEquals(text.length.toLong(), md.charsFed)
  }

  @Test
  fun stableBlocksKeepTheirIdentity() {
    val md = IncrementalMarkdown()
    val first = md.update("# Title\n\nFirst paragraph.\n\nSecond")
    assertTrue(first.stableCount >= 1)
    val second = md.update("# Title\n\nFirst paragraph.\n\nSecond paragraph grows")
    for (i in 0 until first.stableCount) assertSame(first.blocks[i], second.blocks[i])
  }

  @Test
  fun contentThatIsNotAnExtensionStartsOver() {
    val md = IncrementalMarkdown()
    md.update("<think>draft</think> Hello")
    val s = md.update("Hello world")
    assertEquals(1, md.resets)
    assertEquals("Hello world".length, s.blocks.last().node.endOffset)
  }

  /**
   * JVM benchmark for issue #2: a ~40 KB reply streamed in ~10k token-sized updates. Re-parsing
   * the whole message on every update is quadratic; the incremental parser only re-parses the
   * open block. Asserts a wide margin so CI noise can't flip it; numbers go to the job summary.
   */
  @Test
  fun incrementalParsingBeatsFullReparse() {
    val text = sampleReply(60)
    val parts = chunks(text)
    val parser = MarkdownParser(GFMFlavourDescriptor())

    fun full(): Long {
      val t0 = System.nanoTime()
      val sb = StringBuilder()
      // Every 8th update, like a ~50 ms UI cadence at ~25 tok/s with 3–6 chars per token.
      parts.forEachIndexed { i, c ->
        sb.append(c)
        if (i % 8 == 0 || i == parts.lastIndex) parser.buildMarkdownTreeFromString(sb.toString())
      }
      return System.nanoTime() - t0
    }

    fun incremental(): Long {
      val t0 = System.nanoTime()
      val md = IncrementalMarkdown()
      val sb = StringBuilder()
      parts.forEachIndexed { i, c ->
        sb.append(c)
        if (i % 8 == 0 || i == parts.lastIndex) md.update(sb.toString())
      }
      return System.nanoTime() - t0
    }

    repeat(2) { full(); incremental() } // warm-up
    val fullNs = (1..3).minOf { full() }
    val incNs = (1..3).minOf { incremental() }
    val line =
      "Streaming markdown (${text.length} chars, ${parts.size / 8} updates): " +
        "full re-parse ${fullNs / 1_000_000} ms, incremental ${incNs / 1_000_000} ms " +
        "(%.1fx)".format(fullNs.toDouble() / incNs)
    println(line)
    System.getenv("GITHUB_STEP_SUMMARY")?.let { runCatching { File(it).appendText("- $line\n") } }
    assertTrue(line, incNs * 3 < fullNs)
  }
}
