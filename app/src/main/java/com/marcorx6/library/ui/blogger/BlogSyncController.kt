package com.marcorx6.library.ui

import android.content.Context
import android.widget.Toast
import com.marcorx6.library.data.Game
import java.util.concurrent.Executor

internal class BlogSyncController(
    private val context: Context,
    private val executor: Executor,
    private val isSyncing: () -> Boolean,
    private val setSyncing: (Boolean) -> Unit,
    private val fetch: (onBatch: (String, List<Game>) -> Unit) -> String,
    private val postToUi: (() -> Unit) -> Unit,
    private val merge: (List<Game>) -> Pair<Int, Int>,
    private val setBlogId: (String) -> Unit,
    private val refreshAccess: () -> Unit,
    private val renderGames: () -> Unit,
    private val translate: (String) -> String
) {
    fun sync(showResult: Boolean) {
        if (isSyncing()) {
            if (showResult) Toast.makeText(context, translate("المزامنة جارية بالفعل"), Toast.LENGTH_SHORT).show()
            return
        }
        setSyncing(true)
        if (showResult) Toast.makeText(context, translate("جارٍ الاتصال بمدونة MARCO RX6..."), Toast.LENGTH_SHORT).show()
        executor.execute {
            var added = 0
            var updated = 0
            var receivedBatches = 0
            try {
                val blogId = fetch { batchBlogId, games ->
                    if (games.isNotEmpty()) {
                        receivedBatches++
                        postToUi {
                            if (batchBlogId.isNotBlank()) setBlogId(batchBlogId)
                            val result = merge(games)
                            added += result.first
                            updated += result.second
                            // Merge and persist every page immediately, but do not rebuild
                            // the visible library for every page. Re-render once when the
                            // complete feed has arrived to avoid repeated card flicker.
                        }
                    }
                }
                postToUi {
                    if (blogId.isNotBlank()) setBlogId(blogId)
                    setSyncing(false)
                    refreshAccess()
                    renderGames()
                    if (showResult) {
                        Toast.makeText(
                            context,
                            "${translate("اكتملت المزامنة")}: ${translate("تمت إضافة")} $added ${translate("وتحديث")} $updated",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (_: Exception) {
                postToUi {
                    setSyncing(false)
                    if (receivedBatches > 0) {
                        refreshAccess()
                        renderGames()
                    }
                    if (showResult) {
                        val message = if (receivedBatches > 0) {
                            translate("تم تحميل جزء من المكتبة. تحقق من الاتصال لإكمال التحديث.")
                        } else {
                            translate("تعذرت المزامنة. تحقق من اتصال الإنترنت وحاول مجدداً.")
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
