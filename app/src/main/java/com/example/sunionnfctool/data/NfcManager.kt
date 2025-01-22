package com.example.sunionnfctool.data

import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import timber.log.Timber
import java.io.IOException
import java.nio.charset.Charset
import java.util.Locale

class NfcManager(context: Context) {
    private val ndefStorage = NdefMessageStorage(context)
    private val nfcTagKey = "myTag"

    val validActions = listOf(
        NfcAdapter.ACTION_TAG_DISCOVERED,
        NfcAdapter.ACTION_TECH_DISCOVERED,
        NfcAdapter.ACTION_NDEF_DISCOVERED
    )

    fun isTagWritable(intent: Intent): Boolean {
        val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG) ?: return false
        // 嘗試獲取 Ndef 對象
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            return ndef.isWritable // 檢查是否可寫
        }

        // 嘗試檢查 MifareClassic 卡片
        val techList = tag.techList
        if (MifareClassic::class.java.name in techList) {
            val mifareTag = MifareClassic.get(tag)
            try {
                mifareTag.connect()
                val isWritable = !mifareTag.isConnected
                mifareTag.close()
                return isWritable
            } catch (e: Exception) {
                mifareTag.close()
                return false
            }
        }

        // 嘗試檢查 MifareUltralight 卡片
        if (MifareUltralight::class.java.name in techList) {
            val mifareUltralightTag = MifareUltralight.get(tag)
            try {
                mifareUltralightTag.connect()
                val isWritable = mifareUltralightTag.isConnected // MifareUltralight 沒有直接的 isWritable 屬性
                mifareUltralightTag.close()
                return isWritable
            } catch (e: Exception) {
                mifareUltralightTag.close()
                return false
            }
        }

        // 默認情況下返回不可寫
        return false
    }

    // 讀取 NFC Tag 的內容
    fun readFromTag(intent: Intent): List<String> {
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag == null) {
            Timber.d("No NFC tag found")
            return emptyList()
        }

        val ndef = Ndef.get(tag)
        if (ndef == null) {
            Timber.d("This tag does not support NDEF")
            return emptyList()
        }

        try {
            ndef.connect()
            val ndefMessage = ndef.cachedNdefMessage
            if (ndefMessage == null) {
                Timber.d("NDEF Message is null")
                ndefStorage.loadNdefMessage(nfcTagKey)?.let { original ->
                    ndef.writeNdefMessage(original)
                    ndef.close()
                    return parseTextRecords(ndefStorage.loadNdefMessage(nfcTagKey))
                } ?: return emptyList()
            } else {
                ndefStorage.saveNdefMessage(nfcTagKey, ndef.cachedNdefMessage)
            }
            return parseTextRecords(ndefMessage)
        } catch (e: IOException) {
            Timber.e("Error connecting to the NFC tag: ${e.message}")
            return emptyList()
        } finally {
            if (ndef.isConnected) {
                try {
                    ndef.close()
                } catch (e: IOException) {
                    Timber.e("Error closing NFC connection: ${e.message}")
                }
            }
        }
    }

    // 解析 NDEF Message 並取得 Text Records
    private fun parseTextRecords(ndefMessage: NdefMessage?): List<String> {
        val records = ndefMessage?.records ?: return emptyList()
        val textRecords = mutableListOf<String>()

        for (record in records) {
            if (record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                record.type.contentEquals(NdefRecord.RTD_TEXT)
            ) {
                val text = decodeTextRecord(record)
                textRecords.add(text)
            }
        }
        return textRecords
    }

    // 解碼 Text Record
    private fun decodeTextRecord(record: NdefRecord): String {
        val payload = record.payload
        val textEncoding = if ((payload[0].toInt() and 0x80) == 0) Charset.forName("UTF-8") else Charset.forName("UTF-16")
        val languageCodeLength = payload[0].toInt() and 0x3F
        return String(payload, languageCodeLength + 1, payload.size - languageCodeLength - 1, textEncoding)
    }

    // 修改特定 Text Record 並寫回
    fun writeToTag(intent: Intent, textRecords: List<String>, recordIndex: Int, newText: String): Boolean {
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        tag?.let {
            val ndef = Ndef.get(it)
            if (ndef != null) {
                try {
                    ndef.connect()

                    // 備份原始 NDEF Message
                    ndefStorage.saveNdefMessage(nfcTagKey, ndef.cachedNdefMessage)

                    // 修改指定的 Record
                    val updatedRecords = textRecords.toMutableList()
                    if (recordIndex in updatedRecords.indices) {
                        updatedRecords[recordIndex] = newText
                    } else {
                        Timber.e("Invalid record index")
                        return false
                    }

                    // 將更新後的 Records 寫入
                    val ndefMessage = NdefMessage(updatedRecords.map { data -> createTextRecord(data) }.toTypedArray())
                    // 確保新的 NDEF Message 格式正確
                    if (!validateNdefMessage(ndefMessage)) {
                        Timber.e("Invalid NDEF message format")
                        return false
                    }

                    Timber.d("Start writeNdefMessage $ndefMessage")
                    // 寫入標籤
                    ndef.writeNdefMessage(ndefMessage)
                    ndef.close()
                    return true
                } catch (e: Exception) {
                    Timber.e("Error writing to tag: ${e.message}")
                    // 如果發生錯誤，嘗試恢復原始內容
                    try {
                        Timber.e("originalMessage: ${ndefStorage.loadNdefMessage(nfcTagKey)}")
                        ndefStorage.loadNdefMessage(nfcTagKey)?.let { original ->
                            ndef.writeNdefMessage(original)
                        }
                    } catch (restoreError: Exception) {
                        Timber.e("Failed to restore original message: ${restoreError.message}")
                    }
                } finally {
                    try {
                        ndef.close()
                    } catch (closeError: Exception) {
                        Timber.e("Failed to close tag: ${closeError.message}")
                    }
                }
            }
        }
        return false
    }

    private fun validateNdefMessage(message: NdefMessage): Boolean {
        return try {
            message.records.forEach { record ->
                require(record.tnf != NdefRecord.TNF_EMPTY)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // 建立 Text Record
    private fun createTextRecord(text: String): NdefRecord {
        val languageCode = Locale.getDefault().language
        val textBytes = text.toByteArray(Charset.forName("UTF-8"))
        val languageBytes = languageCode.toByteArray(Charset.forName("US-ASCII"))
        val payload = ByteArray(1 + languageBytes.size + textBytes.size)

        payload[0] = languageBytes.size.toByte()
        System.arraycopy(languageBytes, 0, payload, 1, languageBytes.size)
        System.arraycopy(textBytes, 0, payload, 1 + languageBytes.size, textBytes.size)

        return NdefRecord(NdefRecord.TNF_WELL_KNOWN, NdefRecord.RTD_TEXT, ByteArray(0), payload)
    }

    data class TagResult(
        val tagInfo: List<String>,
        val ndefMessage: NdefMessage
    )

    fun handleUnknownTag(intent: Intent): TagResult {
        val tag = intent.parcelable<Tag>(NfcAdapter.EXTRA_TAG) ?: return TagResult(listOf("No tag data available"), NdefMessage(emptyArray()))
        val tagInfo = mutableListOf<String>()

        // 讀取標籤 ID
        val idBytes = intent.getByteArrayExtra(NfcAdapter.EXTRA_ID)
        val idHex = idBytes?.joinToString("") { String.format("%02X", it) } ?: "Unknown ID"
        tagInfo.add("Tag ID: $idHex")

        // 讀取技術列表
        val techList = tag.techList.joinToString(", ")
        tagInfo.add("Supported technologies: $techList")

        // 自定義標籤數據
        val payload = dumpTagData(tag).toByteArray()
        tagInfo.add("Payload: ${String(payload)}")

        val empty = ByteArray(0)
        val record = NdefRecord(NdefRecord.TNF_UNKNOWN, empty, idBytes, payload)
        val msg = NdefMessage(arrayOf(record))

        return TagResult(tagInfo, msg)
    }

    private fun dumpTagData(tag: Tag): String {
        val sb = StringBuilder()
        val id = tag.id
        sb.append("Serial number: ").append(toReversedHex(id)).append('\n')
        val prefix = "android.nfc.tech."
        sb.append("Technologies: ")
        for (tech in tag.techList) {
            sb.append(tech.substring(prefix.length))
            sb.append(", ")
        }
        sb.delete(sb.length - 2, sb.length)
        for (tech in tag.techList) {
            if (tech == MifareClassic::class.java.name) {
                sb.append('\n')
                var type = "Unknown"
                try {
                    val mifareTag = MifareClassic.get(tag)

                    when (mifareTag.type) {
                        MifareClassic.TYPE_CLASSIC -> type = "Classic"
                        MifareClassic.TYPE_PLUS -> type = "Plus"
                        MifareClassic.TYPE_PRO -> type = "Pro"
                    }
                    sb.appendLine("Mifare Classic type: $type")
                    sb.appendLine("Mifare size: ${mifareTag.size} bytes / sectors: ${mifareTag.sectorCount} / blocks: ${mifareTag.blockCount}")
                } catch (e: Exception) {
                    sb.appendLine("Mifare classic error: ${e.message}")
                }
            }
            if (tech == MifareUltralight::class.java.name) {
                sb.append('\n')
                val mifareUlTag = MifareUltralight.get(tag)
                var type = "Unknown"
                when (mifareUlTag.type) {
                    MifareUltralight.TYPE_ULTRALIGHT -> type = "Ultralight"
                    MifareUltralight.TYPE_ULTRALIGHT_C -> type = "Ultralight C"
                }
                sb.append("Mifare Ultralight type: ")
                sb.append(type)
            }
        }
        return sb.toString()
    }

    private fun toReversedHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (i in bytes.indices) {
            if (i > 0) {
                sb.append(":")
            }
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x10) sb.append('0')
            sb.append(Integer.toHexString(b).uppercase(Locale.getDefault()))
        }
        return sb.toString()
    }
}
