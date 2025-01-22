package com.example.sunionnfctool.data

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.example.sunionnfctool.data.record.SmartPoster
import com.example.sunionnfctool.data.record.TextRecord
import com.example.sunionnfctool.data.record.UriRecord

/**
 * Utility class for creating ParsedNdefRecords for Compose.
 */
object NdefMessageParser {

    /** Parse an NdefMessage into a list of ParsedNdefRecordCompose */
    fun parse(message: NdefMessage): List<ParsedNdefRecordCompose> {
        return getRecords(message.records)
    }

    fun getRecords(records: Array<NdefRecord>): List<ParsedNdefRecordCompose> {
        val elements = mutableListOf<ParsedNdefRecordCompose>()
        for (record in records) {
            when {
                UriRecord.isUri(record) -> {
                    elements.add(ParsedNdefRecordCompose.Text(UriRecord.parse(record).uri.toString()))
                }
                TextRecord.isText(record) -> {
                    elements.add(ParsedNdefRecordCompose.Text(TextRecord.parse(record).text))
                }
                SmartPoster.isPoster(record) -> {
                    elements.add(ParsedNdefRecordCompose.Text(SmartPoster.parse(record).toString()))
                }
                else -> {
                    elements.add(
                        ParsedNdefRecordCompose.Text(String(record.payload))
                    )
                }
            }
        }
        return elements
    }
}

/**
 * ParsedNdefRecordCompose is a sealed class representing different types of parsed records.
 */
sealed class ParsedNdefRecordCompose {
    data class Text(val content: String) : ParsedNdefRecordCompose()
}