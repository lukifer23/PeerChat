package com.peerchat.app.util

import org.json.JSONObject

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key)) optString(key)?.takeIf { it.isNotBlank() } else null

fun JSONObject.optFloatOrNull(key: String): Float? =
    if (has(key)) {
        val value = optDouble(key, Double.NaN)
        if (value.isNaN()) null else value.toFloat()
    } else {
        null
    }

fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key)) optInt(key) else null
