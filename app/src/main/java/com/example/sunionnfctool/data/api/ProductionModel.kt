package com.example.sunionnfctool.data.api

data class ProductionModel(
    val model:String?,
    val productionList: List<ProductionItem>?,
)

data class ProductionItem(
    val name: String,
    val detail: String,
    val type: String,
    val version: List<String>
)