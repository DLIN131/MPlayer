package com.mplayer.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ActressLookupTest {
    /** 搜尋結果頁的實際結構（簡化） */
    private fun article(code: String, vararg names: String) = """
        <article class="archive-list"><header class="archive-header">
          <ul class="post-meta clearfix">
            ${names.joinToString("") { """<li class="actress-name"><i class="fa fa-venus"></i><a href="https://example.com/av-actress/$it/" rel="tag">$it</a></li>""" }}
            <li><a href="https://example.com/series/">シリーズ</a></li>
            <li>$code</li>
          </ul>
        </header></article>
    """

    @Test
    fun keepsOnlyMatchingCodes() {
        val html = "<main>" + article("SIRO-5000", "女優A") + article("SIRO-50001", "女優B") + "</main>"
        assertEquals(listOf("女優A"), ActressLookup.parse(html, "siro-5000").map { it.name })
    }

    @Test
    fun acceptsNumericPrefix() {
        val html = "<main>" + article("259LUXU-1800", "女優C", "女優D") + "</main>"
        assertEquals(listOf("女優C", "女優D"), ActressLookup.parse(html, "luxu-1800").map { it.name })
    }

    @Test
    fun stripsVersionSuffix() {
        assertEquals("IPZZ-457", ActressLookup.normalize("ipzz-457-uncensored-leak"))
        assertEquals("SNOS-359", ActressLookup.normalize("snos-359-chinese-subtitle"))
    }

    @Test
    fun noResults() {
        assertEquals(emptyList<WikiActress>(), ActressLookup.parse("<main>0件</main>", "ZZZZ-999"))
    }
}
