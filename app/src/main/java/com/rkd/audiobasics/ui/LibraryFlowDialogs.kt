package com.rkd.audiobasics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rkd.audiobasics.api.account.CloudSnapshot
import com.rkd.audiobasics.api.account.LibraryCounts
import com.rkd.audiobasics.api.account.LibraryFlow
import com.rkd.audiobasics.api.account.LibraryFlowState
import com.rkd.audiobasics.api.account.LocalLibraryStore
import com.rkd.audiobasics.ui.theme.NothingFont

/** Connects the library flow to this device's library, which lives in the ViewModel. */
internal class VmLibraryStore(private val vm: MusicViewModel) : LocalLibraryStore {
    override suspend fun counts(): LibraryCounts = vm.libraryCounts()

    override suspend fun replaceWithCloud(snapshot: CloudSnapshot, wipeLocalOnly: Boolean) {
        vm.replaceLibraryWithCloud(snapshot, wipeLocalOnly)
    }

    override suspend fun clearAll() {
        vm.clearLocalLibrary()
    }

    override suspend fun detachFromCloud() {
        vm.detachLocalLibraryFromCloud()
    }
}

private fun LibraryCounts.describe(): String =
    "$liked liked ${if (liked == 1) "song" else "songs"}, " +
        "$albums ${if (albums == 1) "album" else "albums"} and " +
        "$playlists ${if (playlists == 1) "playlist" else "playlists"}"

/**
 * Shows whichever question or message the library flow currently has for the user. The
 * questions can't be dismissed by tapping outside: each one needs an answer, and every
 * answer is explicit about what it does.
 */
@Composable
internal fun LibraryFlowHost(vm: MusicViewModel, isDarkMode: Boolean) {
    val state by LibraryFlow.state.collectAsState()
    val store = remember(vm) { VmLibraryStore(vm) }

    val textColor = if (isDarkMode) Color.White else Color.Black
    val subTextColor = if (isDarkMode) Color(0xFFAAAAAA) else Color(0xFF666666)
    val container = if (isDarkMode) Color(0xFF1E1E1E) else Color.White
    val danger = Color(0xFFE57373)

    @Composable
    fun Title(text: String) {
        Text(text, fontFamily = NothingFont, fontWeight = FontWeight.Bold, color = textColor)
    }

    @Composable
    fun Body(text: String) {
        Text(text, fontFamily = NothingFont, fontSize = 14.sp, color = subTextColor)
    }

    when (val s = state) {
        is LibraryFlowState.Idle -> Unit

        is LibraryFlowState.Working -> AlertDialog(
            onDismissRequest = {},
            containerColor = container,
            confirmButton = {},
            title = { Title("Please wait") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = textColor
                    )
                    Spacer(Modifier.width(16.dp))
                    Body(s.message)
                }
            }
        )

        is LibraryFlowState.ConfirmOverride -> AlertDialog(
            onDismissRequest = {},
            containerColor = container,
            title = { Title("Replace this device's library?") },
            text = {
                Column {
                    Body("This account already has a library: ${s.cloud.describe()}.")
                    Spacer(Modifier.height(10.dp))
                    Body("Signing in replaces what is on this device (${s.local.describe()}) with the account's library.")
                    Spacer(Modifier.height(10.dp))
                    Body("To combine the two instead, sign in with a new, empty account. To keep a copy first, use Export Library in Settings.")
                }
            },
            confirmButton = {
                TextButton(onClick = { LibraryFlow.replaceLocalWithCloud(store, expectAccountEmpty = false) }) {
                    Text("Replace library", fontFamily = NothingFont, color = danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { LibraryFlow.declineAndLogout() }) {
                    Text("Log out instead", fontFamily = NothingFont, color = textColor)
                }
            }
        )

        is LibraryFlowState.ChooseForEmptyAccount -> AlertDialog(
            onDismissRequest = {},
            containerColor = container,
            title = { Title("This account is empty") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Body("This device has ${s.local.describe()}. What should happen to them?")
                    Body("Empty local library: removes them from this device and starts from the empty account.")
                    if (!LibraryFlow.UPLOAD_AVAILABLE) {
                        Body("Upload library to cloud and sync: coming in a later update.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { LibraryFlow.replaceLocalWithCloud(store, expectAccountEmpty = true) }) {
                    Text("Empty local library", fontFamily = NothingFont, color = danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { LibraryFlow.declineAndLogout() }) {
                    Text("Log out instead", fontFamily = NothingFont, color = textColor)
                }
            }
        )

        is LibraryFlowState.Failed -> AlertDialog(
            onDismissRequest = { LibraryFlow.dismiss() },
            containerColor = container,
            title = { Title("Nothing was changed") },
            text = { Body(s.message) },
            confirmButton = {
                TextButton(onClick = { LibraryFlow.dismiss() }) {
                    Text("Close", fontFamily = NothingFont, color = textColor)
                }
            }
        )

        is LibraryFlowState.Done -> AlertDialog(
            onDismissRequest = { LibraryFlow.dismiss() },
            containerColor = container,
            title = { Title("Done") },
            text = { Body(s.message) },
            confirmButton = {
                TextButton(onClick = { LibraryFlow.dismiss() }) {
                    Text("Close", fontFamily = NothingFont, color = textColor)
                }
            }
        )
    }
}
