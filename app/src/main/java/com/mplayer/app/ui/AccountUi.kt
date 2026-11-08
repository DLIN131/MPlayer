package com.mplayer.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mplayer.app.data.AccountSync
import com.mplayer.app.net.Account
import com.mplayer.app.net.Store
import kotlinx.coroutines.launch

/** 「我的」上方的帳號列：沒登入時邀請登入，登入時顯示帳號和登出 */
@Composable
fun AccountHeader() {
    var showLogin by remember { mutableStateOf(false) }
    var showMerge by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val email = Account.email
            if (email != null) {
                Text("已登入網站帳號", fontSize = 12.sp, color = Muted)
                Text(email, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(if (Account.expired) "登入已過期，請重新登入" else "登入網站帳號", fontSize = 15.sp, color = if (Account.expired) Accent else Color.Unspecified)
                Text("收藏和觀看紀錄在手機、電視、電腦和網站同步", fontSize = 12.sp, color = Muted)
            }
        }
        if (Account.loggedIn) {
            TextButton(onClick = { confirmLogout = true }) { Text("登出", color = Muted) }
        } else {
            Button(onClick = { showLogin = true }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("登入") }
        }
    }

    if (showLogin) {
        LoginDialog(
            onDismiss = { showLogin = false },
            onSuccess = {
                showLogin = false
                if (AccountSync.shouldOfferMerge()) showMerge = true
            },
        )
    }
    if (showMerge) MergeDialog(onDone = { showMerge = false })
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("登出帳號？") },
            text = { Text("登出後收藏和觀看紀錄改回只存在這支手機上。帳號裡的資料不會刪除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch { Account.logout() }
                }) { Text("登出", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

/** 登入：帳密直接送到網站，App 不會存密碼 */
@Composable
fun LoginDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
    var email by remember { mutableStateOf(Account.email ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy || email.isBlank() || password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            val err = Account.login(email, password)
            busy = false
            if (err == null) onSuccess() else error = err
        }
    }

    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("登入網站帳號") },
        text = {
            Column {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("電郵") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密碼") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Accent, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("使用網站的帳號。密碼只會送到網站，不會存在手機上。", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text("登入", color = Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

/** 剛登入：要不要把手機上的收藏加到帳號 */
@Composable
fun MergeDialog(onDone: () -> Unit) {
    val total = Store.favorites.size
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!running) onDone() },
        title = { Text("加入帳號收藏？") },
        text = {
            Column {
                when {
                    result != null -> Text(result!!)
                    running -> {
                        Text("加入中 $done / $total")
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, color = Accent, modifier = Modifier.fillMaxWidth())
                    }
                    else -> Text("這支手機上有 $total 部收藏，要加到網站帳號嗎？加入後在電視、電腦和網站也看得到。")
                }
            }
        },
        confirmButton = {
            if (result != null) TextButton(onClick = onDone) { Text("好", color = Accent) }
            else TextButton(
                enabled = !running,
                onClick = {
                    running = true
                    scope.launch {
                        val ok = AccountSync.uploadLocalFavorites { d, _ -> done = d }
                        running = false
                        result = if (ok == total) "已加入 $ok 部收藏" else "已加入 $ok 部收藏（${total - ok} 部無法加入，可能已下架）"
                    }
                },
            ) { Text("加入", color = Accent) }
        },
        dismissButton = {
            if (result == null && !running) TextButton(onClick = {
                AccountSync.markMergeAsked()
                onDone()
            }) { Text("不用") }
        },
    )
}
