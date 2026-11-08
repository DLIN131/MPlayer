package com.mplayer.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountTest {
    /** 個人頁（收藏）上方使用者選單的實際結構，電郵換成假的 */
    private val loggedIn = """
        <button @click.prevent="showDropdown = 'user'" class="group inline-flex items-center">
        <div x-show="showDropdown === 'user'" class="left-0 z-max origin-top-right absolute mt-2 w-56 rounded-md shadow-lg">
            <div class="px-4 py-3">
                <p class="text-sm leading-5 text-nord0">
                    登入為
                </p>
                <p class="text-sm leading-5 font-medium text-nord0 truncate">
                    someone@example.com
                </p>
            </div>
            <a href="https://example.com/logout" class="text-nord0 block px-4 py-2">登出</a>
        </div>
    """

    private val loggedOut = """
        <div x-init="() => { requireLogin(() => { window.location.reload() }) }">
        <h1 class="text-center text-2xl text-nord4 font-light mb-6">我的影片收藏</h1>
        <a @click.prevent="showLoginModal()">登入</a>
    """

    @Test
    fun readsAccountEmail() {
        assertEquals("someone@example.com", parseLoginEmail(loggedIn))
    }

    @Test
    fun loggedOutPage() {
        assertNull(parseLoginEmail(loggedOut))
    }
}
