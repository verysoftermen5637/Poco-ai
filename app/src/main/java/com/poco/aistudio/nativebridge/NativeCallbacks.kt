package com.poco.aistudio.nativebridge

/**
 * Callback interfaces invoked directly from Rust JNI threads.
 * Method names and signatures must stay synchronized with rust/poco_ai_core and rust/poco_sd_bridge.
 */
interface TokenStreamCallback {
    fun onToken(token: String, tokPerSec: Float)
}

interface ImageProgressCallback {
    fun onProgress(step: Int, totalSteps: Int, timeSec: Float)
}

interface AudioProgressCallback {
    fun onProgress(step: Int, totalSteps: Int)
}
