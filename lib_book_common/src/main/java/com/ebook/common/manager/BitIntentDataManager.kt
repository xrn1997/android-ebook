package com.ebook.common.manager

object BitIntentDataManager {
    val bigData = HashMap<String, Any?>()

    fun getData(key: String): Any? {
        return bigData[key]
    }

    fun putData(key: String, data: Any?) {
        bigData[key] = data
    }

    fun cleanData(key: String) {
        bigData.remove(key)
    }
}
