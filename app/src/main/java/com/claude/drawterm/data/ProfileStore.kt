package com.claude.drawterm.data

import android.content.Context
import android.util.Log
import org.json.JSONArray

/** Saved servers. */
class ProfileStore(context: Context) {
    private val file = JsonFile(context.filesDir, "profiles.json")

    fun load(): List<Profile> = synchronized(JsonFile.lock) {
        val text = file.read() ?: return emptyList()
        try {
            val arr = JSONArray(text)
            List(arr.length()) { Profile.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(JsonFile.TAG, "bad profiles.json", e)
            emptyList()
        }
    }

    fun get(id: String?): Profile? = load().find { it.id == id }

    fun put(p: Profile) = edit { list ->
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
    }

    fun update(id: String, change: (Profile) -> Profile) = edit { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = change(list[i])
    }

    fun delete(id: String) = edit { list -> list.removeAll { it.id == id } }

    private fun edit(change: (MutableList<Profile>) -> Unit) = synchronized(JsonFile.lock) {
        val list = load().toMutableList()
        change(list)
        file.write(JSONArray(list.map { it.toJson() }).toString(1))
    }
}
