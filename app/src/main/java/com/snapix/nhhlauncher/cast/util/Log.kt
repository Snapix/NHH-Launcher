package com.snapix.nhhlauncher.cast.util

import android.util.Log

private const val TAG = "NHH-Cast"
fun logD(message: String) = Log.d(TAG, message)
fun logI(message: String) = Log.i(TAG, message)
fun logW(message: String) = Log.w(TAG, message)
fun logE(message: String, throwable: Throwable? = null) {
    if (throwable == null) Log.e(TAG, message) else Log.e(TAG, message, throwable)
}
