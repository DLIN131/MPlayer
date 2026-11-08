package com.mplayer.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.mplayer.app.data.AccountSync
import com.mplayer.app.net.Account
import com.mplayer.app.net.Site
import com.mplayer.app.net.Store
import kotlinx.coroutines.launch

/** 側邊選單「帳號」：登入網站帳號後收藏和觀看紀錄跟手機、電腦、網站同步 */
@Composable
fun AccountScreen() {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("帳號")
        Column(Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
            if (Account.loggedIn) LoggedInPanel() else LoginPanel()
        }
    }
}

@Composable
private fun LoginPanel() {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy || email.isBlank() || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            error = Account.login(email, password)
            busy = false
        }
    }

    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent,
        cursorColor = Accent,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        focusedLabelColor = Accent,
    )
    if (Account.expired) {
        Text("登入已過期，請重新登入", color = Accent, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
    }
    Text("登入網站帳號後，收藏和觀看紀錄會跟手機、電腦和網站同步。", color = Gray, fontSize = 16.sp)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { androidx.compose.material3.Text("電郵") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        colors = colors,
        modifier = Modifier.width(460.dp),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { androidx.compose.material3.Text("密碼") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        colors = colors,
        modifier = Modifier.width(460.dp),
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TvButton(onClick = ::submit) { Text(if (busy) "登入中…" else "登入") }
    }
    error?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, color = Accent, fontSize = 15.sp)
    }
    Spacer(Modifier.height(16.dp))
    Text("密碼只會送到網站，不會存在電視上。", color = Gray, fontSize = 13.sp)
}

@Composable
private fun LoggedInPanel() {
    var confirmLogout by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var mergeDone by remember { mutableIntStateOf(0) }
    var mergeMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val local = Store.favorites.size

    Text("已登入", color = Gray, fontSize = 15.sp)
    Text(Account.email.orEmpty(), fontSize = 22.sp)
    Spacer(Modifier.height(8.dp))
    Text("「收藏」和「觀看紀錄」現在顯示帳號裡的，在影片頁打開影片也會記進帳號。", color = Gray, fontSize = 15.sp)
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (local > 0) {
            TvButton(onClick = {
                if (merging) return@TvButton
                merging = true
                mergeMsg = null
                scope.launch {
                    val ok = AccountSync.uploadLocalFavorites { d, _ -> mergeDone = d }
                    merging = false
                    mergeMsg = if (ok == local) "已加入 $ok 部收藏" else "已加入 $ok 部收藏（${local - ok} 部無法加入，可能已下架）"
                }
            }) { Text(if (merging) "加入中 $mergeDone / $local" else "把電視上的 $local 部收藏加到帳號") }
        }
        TvButton(onClick = {
            if (!confirmLogout) confirmLogout = true
            else scope.launch { Account.logout() }
        }) { Text(if (confirmLogout) "確定登出？再按一次" else "登出") }
    }
    mergeMsg?.let {
        Spacer(Modifier.height(10.dp))
        Text(it, fontSize = 15.sp)
    }
}

/** 收藏／觀看紀錄：登入時是網站帳號的，沒登入是電視上的 */
@Composable
fun LibraryScreen(favorites: Boolean) {
    var confirmClear by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val loggedIn = Account.loggedIn
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(if (favorites) "收藏" else "觀看紀錄") {
            if (!favorites && (loggedIn || Store.history.isNotEmpty())) {
                TvButton(onClick = {
                    if (!confirmClear) {
                        confirmClear = true
                        return@TvButton
                    }
                    confirmClear = false
                    Store.clearHistory()
                    if (loggedIn) scope.launch {
                        try {
                            Site.clearHistory()
                            AccountSync.invalidateHistory()
                        } catch (e: Exception) {
                            error = "清除帳號觀看紀錄失敗：${e.message}"
                        }
                    }
                }) { Text(if (confirmClear) "確定清除？再按一次" else "清除紀錄") }
            }
        }
        error?.let { Text(it, color = Accent, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 28.dp)) }
        when {
            loggedIn && favorites -> VideoGrid(AccountSync.savedFeed)
            loggedIn -> VideoGrid(AccountSync.historyFeed)
            favorites -> LocalGrid(Store.favorites, "還沒有收藏，在影片頁按「收藏」加入")
            else -> LocalGrid(Store.history, "還沒有觀看紀錄")
        }
    }
}
