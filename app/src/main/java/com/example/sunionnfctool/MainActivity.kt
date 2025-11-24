package com.example.sunionnfctool

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sunionnfctool.data.NdefMessageParser
import com.example.sunionnfctool.data.NfcManager
import com.example.sunionnfctool.data.ParsedNdefRecordCompose
import com.example.sunionnfctool.data.PendingIntent_Mutable
import com.example.sunionnfctool.data.api.ProductionModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var nfcManager: NfcManager
    private val nfcMessages = mutableStateListOf<NdefMessage>()
    private val recordList = mutableStateListOf<String>()
    private var isWaitingForNfc = mutableStateOf(false)
    private var isNfcWritable = mutableStateOf(false)
    private val jsonFileName = mutableStateOf("")
    private val typeContent = mutableStateOf("")
    private val selectedName = mutableStateOf("")
    private val selectedVersion = mutableStateOf("")
    private val searchText = mutableStateOf("")
    private val isWriteSuccess = mutableStateOf(false)
    private val isWriteFail = mutableStateOf(false)
    private val isTestWriteData = false // Test click and write nfc data
    private val selectedMessageIndex = mutableIntStateOf(1)
    private val newTestText = mutableStateOf("77|:|101")
    private var lastWriteData = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcManager = NfcManager(this)
        if (nfcAdapter == null) {
            // 顯示沒有 NFC 功能的對話框
            showNoNfcDialog()
            return
        }

        setContent {
            NfcTagScreen(
                nfcMessages = nfcMessages,
                recordList = recordList,
                isNfcWritable = isNfcWritable,
                isWaitingForNfc = isWaitingForNfc,
                newTextState = newTestText,
                selectedMessageIndexState = selectedMessageIndex,
                jsonFileName = jsonFileName,
                typeContent = typeContent,
                isWriteSuccess = isWriteSuccess,
                isWriteFail = isWriteFail,
                selectedName = selectedName,
                selectedVersion = selectedVersion,
                searchText = searchText,
                isTestWriteData = isTestWriteData,
                lastWriteData = lastWriteData
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (nfcAdapter?.isEnabled == false) {
            openNfcSettings()
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent_Mutable
        )
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Timber.d("onNewIntent:$intent \nisWaitingForNfc:${isWaitingForNfc.value} selectedMessageIndex:${selectedMessageIndex.intValue} newTestText:${newTestText.value}")
        isNfcWritable.value = nfcManager.isTagWritable(intent)
        if (intent.action in nfcManager.validActions && isWaitingForNfc.value) {
            val textRecords = mutableListOf<String>()
            nfcMessages.forEach{ nfcMessage ->
                val parsedRecords = NdefMessageParser.parse(nfcMessage)
                parsedRecords.forEach { record ->
                    if (record is ParsedNdefRecordCompose.Text) {
                        textRecords.add(record.content)
                    }
                }
                Timber.d("nfcMessage:$nfcMessage\nparsedRecords:${parsedRecords}\n" +
                        "textRecords:${textRecords}")
            }
            // 寫入修改過的消息到 NFC 標籤
            val success = nfcManager.writeToTag(intent, textRecords, selectedMessageIndex.intValue, newTestText.value)
            if (success) {
                Timber.d("Successfully wrote to NFC tag.")
                isWriteSuccess.value = true
                isWriteFail.value = false
                lastWriteData.value = newTestText.value
            } else {
                Timber.e("Failed to write to NFC tag.")
                isWriteFail.value = true
            }
            // 停止等待 NFC 標籤
            isWaitingForNfc.value = false
        } else {
            resolveIntent(intent)
            Toast.makeText(this, "Read NFC tag Success", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showNoNfcDialog() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.no_nfc)
            .setNeutralButton(R.string.close_app) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun openNfcSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_NFC)
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        }
        startActivity(intent)
    }

    private fun resetTagData(){
        nfcMessages.clear()
        recordList.clear()
        jsonFileName.value = ""
        typeContent.value = ""
        isWriteSuccess.value = false
        isWriteFail.value = false
    }

    private fun resolveIntent(intent: Intent) {
        if (intent.action in nfcManager.validActions) {
            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let {
                resetTagData()
                // 使用 readFromTag 來讀取並解析標籤內容
                val textRecords = nfcManager.readFromTag(intent)
                if (textRecords.isNotEmpty()) {
                    // 更新 nfcMessages 和 recordList
                    val rawMessages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                    val messages = mutableListOf<NdefMessage>()
                    rawMessages?.forEach { raw ->
                        messages.add(raw as NdefMessage)
                    }
                    nfcMessages.addAll(messages)
                    recordList.addAll(textRecords)
                    if(recordList.isNotEmpty()){
                        jsonFileName.value = getJsonFileName(recordList[0]) ?: ""
                        typeContent.value = recordList[1]
                        Timber.d("jsonFileName: ${jsonFileName.value} typeContent: ${typeContent.value}")
                    }
                    Timber.d("nfcMessages:${nfcMessages.toList()} \nrecordList:${recordList.toList()}")
                } else {
                    val (tagInfo, ndefMessage) = nfcManager.handleUnknownTag(intent)
                    Timber.d("Unknown tag type detected: $tagInfo")
                    recordList.addAll(tagInfo)
                    nfcMessages.add(ndefMessage)
                }
            }
        }
    }

    private fun getJsonFileName(input: String): String? {
        // 將輸入字串用 "|:|" 分割
        val parts = input.split("|:|")
        // 檢查分割後的結果是否有至少兩個部分
        if (parts.size >= 2) {
            return "${parts[0]}/${parts[0]}_${parts[1]}"
        }
        // 如果格式不符合，回傳 null
        return null
    }
}


@Composable
fun NfcTagScreen(
    nfcMessages: List<NdefMessage>,
    recordList: MutableList<String>,
    isNfcWritable: MutableState<Boolean>,
    isWaitingForNfc: MutableState<Boolean>,
    newTextState: MutableState<String>,
    selectedMessageIndexState: MutableState<Int>,
    isWriteSuccess: MutableState<Boolean>,
    isWriteFail: MutableState<Boolean>,
    selectedName: MutableState<String>,
    selectedVersion: MutableState<String>,
    searchText: MutableState<String>,
    jsonFileName: MutableState<String>,
    typeContent: MutableState<String>,
    isTestWriteData: Boolean,
    lastWriteData: MutableState<String>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()       // 加上 padding 避免 UI 被狀態列擋住
            .navigationBarsPadding()   // 避免 UI 被導航列擋住
            .padding(16.dp)
    ) {
        Text(
            text = "Sunion NFC Tool",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (nfcMessages.isEmpty()) {
            Text(text = "Start to tag NFC.", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                items(nfcMessages.size) { index ->
                    val message = nfcMessages[index]
                    NfcMessageItem(
                        isNfcWritable = isNfcWritable,
                        message = message,
                        recordList = recordList,
                        onMessageClick = { parsedRecordIndex ->
                            if(isTestWriteData) {
                                selectedMessageIndexState.value = parsedRecordIndex
                            }
                        }
                    )
                }
                item {
                    if (selectedMessageIndexState.value != -1 && isTestWriteData) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = newTextState.value,
                                onValueChange = { newTextState.value = it },
                                label = { Text("New text for Record ${selectedMessageIndexState.value}", style = MaterialTheme.typography.bodyLarge) },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            Button(
                                onClick = {
                                    isWaitingForNfc.value = true
                                },
                                enabled = if (isNfcWritable.value && !isWriteSuccess.value) !isWaitingForNfc.value else false
                            ) {
                                Text(text = "Write Data", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }

                    selectedName.value = ""
                    selectedVersion.value = ""
                    searchText.value = ""
                    
                    FetchAndDisplayJson(
                        fileName = jsonFileName.value,
                        typeContent = typeContent.value,
                        isNfcWritable = isNfcWritable,
                        isWaitingForNfc = isWaitingForNfc,
                        newTextState = newTextState,
                        isWriteSuccess = isWriteSuccess,
                        isWriteFail = isWriteFail,
                        selectedName = selectedName,
                        selectedVersion = selectedVersion,
                        searchText = searchText,
                        lastWriteData = lastWriteData
                    )
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val versionName = BuildConfig.VERSION_NAME.substringBeforeLast(".")
            Text(
                text = stringResource(id = R.string.launcher_version, versionName),
                color = colorResource(id = R.color.black),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

@Composable
fun NfcMessageItem(
    isNfcWritable: MutableState<Boolean>,
    message: NdefMessage,
    recordList: MutableList<String>,
    onMessageClick: (Int) -> Unit)
{
    val now = Date()
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // 將 NdefMessage 解析為 ParsedNdefRecords
    val parsedRecords = NdefMessageParser.parse(message)
    Timber.d("parsedRecords:$parsedRecords")

    val isNfcWritableText = if (isNfcWritable.value) "Yes" else "No"

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)) {
        Text(text = "Time: ${timeFormat.format(now)}", style = MaterialTheme.typography.bodyLarge)
        Text(text = "NFC Writable: $isNfcWritableText", style = MaterialTheme.typography.bodyLarge)

        if (parsedRecords.isEmpty()) {
            Text(text = "No records found.", style = MaterialTheme.typography.bodyLarge)
        } else {
            parsedRecords.forEachIndexed { index, record ->
                // 設定每個記錄的點擊事件，點擊後傳遞對應的 index
                recordList.add(record.toString())
                Column(modifier = Modifier.clickable { onMessageClick(index) }) {
                    ParsedNdefRecordView(record = record, index = index)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
fun ParsedNdefRecordView(record: ParsedNdefRecordCompose, index: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Text(text = "Record $index:", style = MaterialTheme.typography.bodyLarge)
        Text(text = record.toString(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ProductionDropdowns(
    productionModel: ProductionModel,
    fileName: String,
    typeContent: String,
    isNfcWritable: MutableState<Boolean>,
    isWaitingForNfc: MutableState<Boolean>,
    newTextState: MutableState<String>,
    isWriteSuccess: MutableState<Boolean>,
    isWriteFail: MutableState<Boolean>,
    selectedName: MutableState<String>,
    selectedVersion: MutableState<String>,
    searchText: MutableState<String>,
    lastWriteData: MutableState<String>,
) {
    val originType = typeContent.split("|:|")[0]
    val originVersion = typeContent.split("|:|")[1]
    Timber.d("originType:$originType originVersion:$originVersion lastWriteData:${lastWriteData.value}")
    var mappingContent = ""
    if(originType.isNotBlank() && originVersion.isNotBlank()){
        val name = productionModel.productionList?.firstOrNull { it.type == originType && it.version.contains(originVersion) }?.name ?: ""
        val version = productionModel.productionList?.firstOrNull { it.name == name }?.version?.firstOrNull { it == originVersion } ?: ""
        Timber.d("name:$name version:$version")
        if(name.isNotBlank() && version.isNotBlank()){
            mappingContent = "Name:$name, Version:$version"
        }
    }

    // 選項列表
    val nameOptions = productionModel.productionList?.map { it.name } ?: emptyList()
    val versionOptions = productionModel.productionList?.firstOrNull { it.name == selectedName.value }?.version ?: emptyList()
    val type = productionModel.productionList?.firstOrNull { it.name == selectedName.value }?.type ?: ""
    val detail = productionModel.productionList?.firstOrNull { it.name == selectedName.value }?.detail ?: ""

    val writeData = "$type|:|${selectedVersion.value}"
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                if(lastWriteData.value.isNotBlank() && selectedName.value.isBlank() && selectedVersion.value.isBlank()){
                    val isTheSameData = if(typeContent == lastWriteData.value) colorResource(R.color.success) else colorResource(R.color.error)
                    Text(text = "Last Write Data: ${lastWriteData.value}", color = isTheSameData, style = MaterialTheme.typography.bodyLarge)
                }
                if(mappingContent.isNotBlank()){
//                    Text(text = "Origin $mappingContent", color = colorResource(R.color.blue_500), style = MaterialTheme.typography.bodyLarge)
                }
                Text(text = "File Name: $fileName.json", style = MaterialTheme.typography.bodyLarge)
                Text(text = "Name: ${selectedName.value}", style = MaterialTheme.typography.bodyLarge)
                Text(text = "Detail: $detail", style = MaterialTheme.typography.bodyLarge)
                Text(text = "Type: $type", style = MaterialTheme.typography.bodyLarge)
                Text(text = "Version: ${selectedVersion.value}", style = MaterialTheme.typography.bodyLarge)
                Text(text = "Write Data: $writeData", style = MaterialTheme.typography.bodyLarge)
                TextField(
                    value = searchText.value,
                    onValueChange = { searchText.value = it },
                    readOnly = false,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(text = "Filter Select Name") }
                )
            }
            val filteredNameOptions = nameOptions.filter { it.contains(searchText.value, ignoreCase = true) }
            Column(
                modifier = Modifier
                    .fillMaxWidth().padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Type Dropdown
                    DropdownMenuBox(
                        label = "Select Name",
                        options = filteredNameOptions,
                        selectedOption = selectedName.value,
                        onOptionSelected = { newName ->
                            selectedName.value = newName
                            selectedVersion.value = "" // 直接重置版本選擇
                        },
                    )

                    Spacer(modifier = Modifier.width(30.dp))

                    // Version Dropdown
                    DropdownMenuBox(
                        label = "Select Version",
                        options = versionOptions,
                        selectedOption = selectedVersion.value,
                        onOptionSelected = { selectedVersion.value = it },
                    )
                }
                Button(
                    onClick = {
                        newTextState.value = writeData
                        isWaitingForNfc.value = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colorResource(id = R.color.primary)),
                    enabled = if (isNfcWritable.value && selectedName.value.isNotBlank() && selectedVersion.value.isNotBlank() && !isWriteSuccess.value) !isWaitingForNfc.value else false,
                    modifier = Modifier.wrapContentWidth().padding(vertical = 16.dp)
                ) {
                    Text(text = if (isWaitingForNfc.value) "Wait NFC tag..." else "Write Data", style = MaterialTheme.typography.bodyLarge)
                }
                if(isWriteSuccess.value){
                    Text(text = "Write Success & Re-sensing NFC", color = colorResource(R.color.success), fontSize = 24.sp)
                }
                if(isWriteFail.value){
                    Text(text = "Write Fail", color = colorResource(R.color.error), fontSize = 24.sp)
                }
            }
        }
    }
}

@Composable
fun DropdownMenuBox(
    label: String,
    options: List<String>?,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val expanded = remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally // 👈 讓 label 置中
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutlinedButton(
                onClick = { expanded.value = true },
                modifier = Modifier
                    .widthIn(max = 180.dp) //限制最大寬度避免撐開 layout
                    .wrapContentWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = colorResource(id = R.color.secondary_variant)
            )) {
                Text(
                    text = selectedOption.ifEmpty { if(label.contains("Name")) "Name" else "Version" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(
            expanded = expanded.value,
            onDismissRequest = { expanded.value = false }
        ) {
            options?.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        onOptionSelected(option)
                        expanded.value = false
                    }
                )
            }
        }
    }
}

@Composable
fun FetchAndDisplayJson(
    fileName: String,
    typeContent: String,
    isNfcWritable: MutableState<Boolean>,
    isWaitingForNfc: MutableState<Boolean>,
    newTextState: MutableState<String>,
    isWriteSuccess: MutableState<Boolean>,
    isWriteFail: MutableState<Boolean>,
    selectedName: MutableState<String>,
    selectedVersion: MutableState<String>,
    searchText: MutableState<String>,
    lastWriteData: MutableState<String>
) {
    val serverUrl = BuildConfig.API_GATEWAY_ENDPOINT
    val url = "$serverUrl$fileName.json"
    val productionModel = remember { mutableStateOf<ProductionModel?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val httpErrorMsg = remember { mutableStateOf("") }

    LaunchedEffect(fileName) {
        if (fileName.isBlank()) {
            productionModel.value = null
            return@LaunchedEffect
        }

        coroutineScope.launch(Dispatchers.IO) {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    httpErrorMsg.value = ""
                    val jsonString = response.body?.string()
                    jsonString?.let {
                        try {
                            val model = Gson().fromJson(it, ProductionModel::class.java)
                            Timber.d("model:$model")
                            withContext(Dispatchers.Main) {
                                if(model.model.isNullOrBlank() || model.productionList.isNullOrEmpty()) {
                                    productionModel.value = null
                                    httpErrorMsg.value = "Json format error."
                                } else {
                                    productionModel.value = model
                                }
                            }
                        } catch (e: JsonSyntaxException) {
                            // 如果 JSON 格式錯誤，將 productionModel 設為空值
                            withContext(Dispatchers.Main) {
                                productionModel.value = null
                                httpErrorMsg.value = "Json format error."
                            }
                            Timber.e("Json parsing error: ${e.message}")
                        }
                    }
                } else {
                    Timber.e("Error file name: $fileName.json, url: $url, http error: ${response.code}")
                    httpErrorMsg.value = "Error file name: $fileName.json \n\nHttp error: ${response.code}"
                }
            } catch (e: IOException) {
                Timber.e("Error fetching json: ${e.message}")
                httpErrorMsg.value = "Error fetching json: ${e.message}"
            }
        }
    }

    // UI 顯示部分
    if (fileName.isBlank()) {
        Text(text = "Data format error.", color = colorResource(R.color.error), fontSize = 24.sp, style = MaterialTheme.typography.bodyLarge)
    } else {
        productionModel.value?.let { model ->
            ProductionDropdowns(model, fileName, typeContent, isNfcWritable, isWaitingForNfc, newTextState, isWriteSuccess, isWriteFail, selectedName, selectedVersion, searchText, lastWriteData)
        } ?: Text(text = httpErrorMsg.value.ifBlank { "Loading..." }, color = if(httpErrorMsg.value.isBlank()) colorResource(R.color.black) else colorResource(R.color.error), fontSize = 24.sp, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewNfcTagScreen() {
    NfcTagScreen(
        nfcMessages = listOf(),
        recordList = mutableListOf("[FI0047|:|102|:|70,71,72,73,74,75,76,77,78,79,7A,7B,7C, 73|:|203]"),
        isNfcWritable = remember { mutableStateOf(false) },
        isWaitingForNfc = remember { mutableStateOf(false) },
        newTextState = remember { mutableStateOf("22:103") },
        selectedMessageIndexState = remember { mutableIntStateOf(1) },
        jsonFileName = remember { mutableStateOf("FI0047_102.json") },
        typeContent = remember { mutableStateOf("74|:|114") },
        isWriteSuccess = remember { mutableStateOf(false) },
        isWriteFail = remember { mutableStateOf(false) },
        selectedName = remember { mutableStateOf("") },
        selectedVersion = remember { mutableStateOf("") },
        searchText = remember { mutableStateOf("") },
        isTestWriteData = false,
        lastWriteData = remember { mutableStateOf("") },
    )
}