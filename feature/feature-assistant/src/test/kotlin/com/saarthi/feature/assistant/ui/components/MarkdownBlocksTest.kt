package com.saarthi.feature.assistant.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {

    @Test
    fun `plain paragraphs stay one text block`() {
        val text = "First paragraph.\n\nSecond paragraph."
        assertEquals(listOf(MdBlock.Text(text)), splitMarkdownBlocks(text))
    }

    @Test
    fun `bullets and numbered items become list rows with nesting`() {
        val blocks = splitMarkdownBlocks("Steps:\n1. Boil water\n2. Add tea\n  - two spoons\nDone.")
        assertEquals(
            listOf(
                MdBlock.Text("Steps:"),
                MdBlock.ListItem("1.", "Boil water", level = 0),
                MdBlock.ListItem("2.", "Add tea", level = 0),
                MdBlock.ListItem("•", "two spoons", level = 1),
                MdBlock.Text("Done."),
            ),
            blocks,
        )
    }

    @Test
    fun `pipe table is parsed into header and rows`() {
        val text = "Compare:\n| Plan | Price |\n|---|---:|\n| Basic | ₹99 |\n| Pro | ₹199 |\nPick one."
        val blocks = splitMarkdownBlocks(text)
        assertEquals(MdBlock.Text("Compare:"), blocks[0])
        assertEquals(
            MdBlock.Table(listOf("Plan", "Price"), listOf(listOf("Basic", "₹99"), listOf("Pro", "₹199"))),
            blocks[1],
        )
        assertEquals(MdBlock.Text("Pick one."), blocks[2])
    }

    @Test
    fun `a pipe line without a separator row is not a table`() {
        val text = "| just a line |"
        assertEquals(listOf(MdBlock.Text(text)), splitMarkdownBlocks(text))
    }

    @Test
    fun `list-looking lines inside a code fence are left alone`() {
        val text = "```\n- not a bullet\n1. not a number\n```"
        assertEquals(listOf(MdBlock.Text(text)), splitMarkdownBlocks(text))
    }

    @Test
    fun `streaming cleanup hides raw markdown syntax`() {
        val out = lightStreamingMarkdown("## Title\n**Bold** point\n- item\n```\ncode\n```")
        assertEquals("Title\nBold point\n•  item\ncode", out)
        assertTrue(!out.contains("**") && !out.contains("#"))
    }
}
