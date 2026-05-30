package com.yt.lite.api.cipher

object FunctionNameExtractor {
    fun extractNFunctionName(playerJs: String): String? {
        val nFuncNameMatch = Regex("""\.get\(\"n\"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\([a-zA-Z0-9]\)""").find(playerJs)
            ?: Regex("""\b([a-zA-Z0-9$]+)\s*=\s*function\(\s*([a-zA-Z0-9$]+)\s*\)\s*\{\s*var\s+([a-zA-Z0-9$]+)\s*=\s*\2\.split\(\"\"\);\s*var\s+([a-zA-Z0-9$]+)\s*=\s*\[.*?\];""").find(playerJs)
        
        return nFuncNameMatch?.groupValues?.get(1)
    }
}
