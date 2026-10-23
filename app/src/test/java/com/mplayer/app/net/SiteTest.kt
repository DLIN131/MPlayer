package com.mplayer.app.net

import org.junit.Assert.assertEquals
import org.junit.Test

class SiteTest {
    /** 影片頁裡打包過的播放網址片段（原樣複製，含跳脫字元） */
    private val page = """
        let source
        eval(function(p,a,c,k,e,d){e=function(c){return c.toString(36)};if(!''.replace(/^/,String)){while(c--){d[c.toString(a)]=k[c]||c.toString(a)}k=[function(e){return d[e]}];e=function(){return'\\w+'};c=1};while(c--){if(k[c]){p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c])}}return p}('e=\'8://7.6/5-4-3-2-1/d.0\';c=\'8://7.6/5-4-3-2-1/a/9.0\';b=\'8://7.6/5-4-3-2-1/a/9.0\';',15,15,'m3u8|c9447e4618dd|a535|438d|4e30|362737b8|com|cdn|https|video|720p|source1280|source842|playlist|source'.split('|'),0,{}))
        const video = document.querySelector('video.player')
    """

    @Test
    fun unpacksPlaylist() {
        assertEquals(
            "https://cdn.com/362737b8-4e30-438d-a535-c9447e4618dd/playlist.m3u8",
            Site.findPlaylist(page),
        )
    }
}
