package com.mplayer.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsTest {
    private val base = "https://cdn.example.com/abc/playlist.m3u8"

    @Test
    fun parsesVariantsHighestFirst() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=1046784,AVERAGE-BANDWIDTH=658958,CODECS="avc1.64001e,mp4a.40.2",RESOLUTION=640x360,CLOSED-CAPTIONS=NONE
            360p/video.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2835040,AVERAGE-BANDWIDTH=1773886,CODECS="avc1.64001f,mp4a.40.2",RESOLUTION=1280x720,CLOSED-CAPTIONS=NONE
            720p/video.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            1080p/video.m3u8
        """.trimIndent().replace("\n", "\r\n")
        val v = Hls.variants(master, base)
        assertEquals(listOf(1080, 720, 360), v.map { it.height })
        assertEquals("https://cdn.example.com/abc/720p/video.m3u8", v[1].url)
        assertEquals(1773886L, v[1].bandwidth)
        assertEquals(5000000L, v[0].bandwidth)
    }

    @Test
    fun parsesSegments() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.000000,
            video0.jpeg
            #EXTINF:2.5,
            video1.jpeg
            #EXT-X-ENDLIST
        """.trimIndent()
        val s = Hls.segments(media, "https://cdn.example.com/abc/720p/video.m3u8")
        assertEquals(2, s.size)
        assertEquals("https://cdn.example.com/abc/720p/video1.jpeg", s[1].url)
        assertEquals(2.5, s[1].duration, 0.001)
    }

    @Test(expected = Hls.UnsupportedException::class)
    fun rejectsEncrypted() {
        Hls.segments("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:4,\na.ts", base)
    }
}
