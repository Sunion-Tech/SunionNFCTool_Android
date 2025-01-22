package com.example.sunionnfctool.data

import android.content.Context
import android.nfc.NdefMessage
import android.util.Base64

class NdefMessageStorage(context: Context) {
    private val sharedPreferences = context.getSharedPreferences("NdefStorage", Context.MODE_PRIVATE)

    // 儲存 NdefMessage
    fun saveNdefMessage(key: String, message: NdefMessage) {
        val encodedMessage = encodeNdefMessage(message)
        sharedPreferences.edit().putString(key, encodedMessage).apply()
    }

    // 讀取 NdefMessage
    fun loadNdefMessage(key: String): NdefMessage? {
        val encodedMessage = sharedPreferences.getString(key, null) ?: return null
        return decodeNdefMessage(encodedMessage)
    }

    // 刪除 NdefMessage
    fun deleteNdefMessage(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }

    // 編碼 NdefMessage 為 Base64
    private fun encodeNdefMessage(message: NdefMessage): String {
        val byteArray = message.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    // 解碼 Base64 為 NdefMessage
    private fun decodeNdefMessage(encodedMessage: String): NdefMessage {
        val byteArray = Base64.decode(encodedMessage, Base64.DEFAULT)
        return NdefMessage(byteArray)
    }
}