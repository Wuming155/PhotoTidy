package com.phototidy.core.media

import android.content.Context
import android.content.SharedPreferences
import com.phototidy.core.model.PendingMove
import org.json.JSONArray
import org.json.JSONObject

/**
 * 整理会话的落盘快照。
 *
 * 解决的是 README 里承认过的那条取舍：会话级提交把待办放在内存里，
 * 整理到一半应用被系统回收（或者用户被系统清后台），排好的队就全没了。
 *
 * 三条设计约束：
 *  1. **只存 id，不存照片**。排队状态需要的是「哪几张」和「挪到哪」，
 *     完整元数据在恢复时回 MediaStore 换回来即可 —— 期间已经消失的照片自然被剔除，
 *     不需要任何额外的一致性判定。于是快照体积与照片总数无关（只有几 KB）。
 *  2. **写的是应用私有 SharedPreferences**，不碰系统相册，也不新增任何系统授权。
 *     整理的「零打扰」语义没有被破坏：用户依然一次系统弹窗都看不到。
 *  3. **`apply()` 异步落盘**，所以每次滑动都写一份也不会阻塞 UI 线程。
 *
 * 注意：这里保存的是**意图**（打算删哪些、打算挪到哪），不是事实。
 * 照片本身从头到尾没有被改动过 —— 应用被回收时丢掉的也只是「还没提交的打算」。
 */
class SessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("phototidy_session", Context.MODE_PRIVATE)

    // ----------------------------------------------------------- 待清除队列（全局）

    fun saveStaging(imageIds: List<Long>) {
        prefs.edit().putString(KEY_STAGING, JSONArray(imageIds).toString()).apply()
    }

    fun loadStaging(): List<Long> = readIdArray(prefs.getString(KEY_STAGING, null))

    fun clearStaging() {
        prefs.edit().remove(KEY_STAGING).apply()
    }

    // ----------------------------------------------------------- 待移动表（按范围）

    fun savePendingMoves(scopeKey: String, moves: List<PendingMove>) {
        val array = JSONArray()
        moves.forEach { move ->
            array.put(
                JSONObject().apply {
                    put("id", move.imageId)
                    put("bucket", move.bucketId)
                    put("name", move.albumName)
                    put("albumPath", move.albumPath)
                    put("targetPath", move.targetPath)
                },
            )
        }
        prefs.edit().putString(pendingKey(scopeKey), array.toString()).apply()
    }

    fun loadPendingMoves(scopeKey: String): List<PendingMove> {
        val raw = prefs.getString(pendingKey(scopeKey), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optLong("id", -1L)
                if (id < 0L) return@mapNotNull null
                PendingMove(
                    imageId = id,
                    targetPath = item.optString("targetPath"),
                    albumName = item.optString("name"),
                    bucketId = item.optLong("bucket", 0L),
                    albumPath = item.optString("albumPath"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clearPendingMoves(scopeKey: String) {
        prefs.edit().remove(pendingKey(scopeKey)).apply()
    }

    // ------------------------------------------------------------------ 内部

    private fun pendingKey(scopeKey: String) = "$PREFIX_PENDING$scopeKey"

    private fun readIdArray(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map(array::getLong)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_STAGING = "staging_ids"
        const val PREFIX_PENDING = "pending_moves:"
    }
}
