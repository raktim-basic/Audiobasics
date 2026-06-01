package com.yt.lite.api.cipher

import timber.log.Timber
import java.security.MessageDigest

object FunctionNameExtractor {
    private const val TAG = "Metrolist_CipherFnExtract"

    data class SigFunctionInfo(
        val name: String,
        val constantArg: Int?,
        val constantArgs: List<Int>? = null,
        val preprocessFunc: String? = null,
        val preprocessArgs: List<Int>? = null,
        val isHardcoded: Boolean = false
    )

    data class NFunctionInfo(
        val name: String,
        val arrayIndex: Int?,
        val constantArgs: List<Int>? = null,
        val isHardcoded: Boolean = false
    )

    data class HardcodedPlayerConfig(
        val sigFuncName: String,
        val sigConstantArg: Int?,
        val sigConstantArgs: List<Int>? = null,
        val sigPreprocessFunc: String? = null,
        val sigPreprocessArgs: List<Int>? = null,
        val nFuncName: String,
        val nArrayIndex: Int?,
        val nConstantArgs: List<Int>?,
        val signatureTimestamp: Int
    )

    private val KNOWN_PLAYER_CONFIGS = mapOf(
        "74edf1a3" to HardcodedPlayerConfig(
            sigFuncName = "JI",
            sigConstantArg = 48,
            sigConstantArgs = listOf(48, 1918),
            sigPreprocessFunc = "f1",
            sigPreprocessArgs = listOf(1, 6528),
            nFuncName = "GU",
            nArrayIndex = null,
            nConstantArgs = listOf(6, 6010),
            signatureTimestamp = 20522
        ),
        "f4c47414" to HardcodedPlayerConfig(
            sigFuncName = "hJ",
            sigConstantArg = 6,
            sigConstantArgs = listOf(6),
            sigPreprocessFunc = null,
            sigPreprocessArgs = null,
            nFuncName = "",
            nArrayIndex = null,
            nConstantArgs = null,
            signatureTimestamp = 20543
        ),
        "57f5d44f" to HardcodedPlayerConfig(
            sigFuncName = "",
            sigConstantArg = null,
            sigConstantArgs = null,
            sigPreprocessFunc = null,
            sigPreprocessArgs = null,
            nFuncName = "",
            nArrayIndex = null,
            nConstantArgs = null,
            signatureTimestamp = 20591
        )
    )

    private val Q_ARRAY_PATTERN = Regex("""var\s+Q\s*=\s*"[^"]+"\s*\.\s*split\s*\(\s*"\}"\s*\)""")

    private val PLAYER_HASH_PATTERNS = listOf(
        Regex("""jsUrl['":\s]+[^"']*?/player/([a-f0-9]{8})/"""),
        Regex("""player_ias\.vflset/[^/]+/([a-f0-9]{8})/"""),
        Regex("""/s/player/([a-f0-9]{8})/""")
    )

    private val SIG_FUNCTION_PATTERNS = listOf(
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\)"""),
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\.\s*[a-z]\s*\)"""),
        Regex("""\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("\\bm=([a-zA-Z0-9\\$]{2,})\\(decodeURIComponent\\(h\\.s\\)\\)"),
        Regex("""\bc\s*&&\s*d\.set\([^,]+\s*,\s*(?:encodeURIComponent\s*\()([a-zA-Z0-9$]+)\("""),
        Regex("""\bc\s*&&\s*[a-z]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\(""")
    )

    private val N_FUNCTION_PATTERNS = listOf(
        Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(([a-zA-Z0-9])\)"""),
        Regex("""\.get\("n"\)\)\s*&&\s*\(([a-zA-Z0-9$]+)\s*=\s*([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(\1\)"""),
        Regex("""\.get\("n"\);if\([a-zA-Z0-9$]+\)\s*\{[^}]*match"""),
        Regex("""\(\s*([a-zA-Z0-9$]+)\s*=\s*String\.fromCharCode\(110\)"""),
        Regex("""([a-zA-Z0-9$]+)\s*=\s*function\([a-zA-Z0-9]\)\s*\{[^}]*?enhanced_except_""")
    )

    fun hasQArrayObfuscation(playerJs: String): Boolean {
        val hasQArray = Q_ARRAY_PATTERN.containsMatchIn(playerJs)
        Timber.tag(TAG).d("Q-array obfuscation check: hasQArray=\$hasQArray")
        if (hasQArray) {
            val match = Q_ARRAY_PATTERN.find(playerJs)
            if (match != null) {
                val start = match.range.first
                val qDefEnd = playerJs.indexOf(";", start)
                if (qDefEnd > start) {
                    val qDef = playerJs.substring(start, qDefEnd)
                    val elementCount = qDef.count { it == '}' } + 1
                    Timber.tag(TAG).d("Q-array detected with ~\$elementCount elements")
                }
            }
        }
        return hasQArray
    }

    fun extractPlayerHash(playerJs: String): String? {
        for ((index, pattern) in PLAYER_HASH_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        val contentToHash = playerJs.take(10000)
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(contentToHash.toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun getHardcodedConfig(playerHash: String): HardcodedPlayerConfig? {
        return KNOWN_PLAYER_CONFIGS[playerHash]
    }

    fun extractSigFunctionInfo(playerJs: String, knownHash: String? = null): SigFunctionInfo? {
        for ((index, pattern) in SIG_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                val name = match.groupValues[1]
                val constArg = if (match.groupValues.size > 2) match.groupValues[2].toIntOrNull() else null
                return SigFunctionInfo(name, constArg, isHardcoded = false)
            }
        }
        if (hasQArrayObfuscation(playerJs)) {
            val hashToUse = knownHash ?: extractPlayerHash(playerJs)
            if (hashToUse != null) {
                val config = getHardcodedConfig(hashToUse)
                if (config != null) {
                    return SigFunctionInfo(
                        name = config.sigFuncName,
                        constantArg = config.sigConstantArg,
                        constantArgs = config.sigConstantArgs,
                        preprocessFunc = config.sigPreprocessFunc,
                        preprocessArgs = config.sigPreprocessArgs,
                        isHardcoded = true
                    )
                }
            }
        }
        return null
    }

    fun extractNFunctionInfo(playerJs: String, knownHash: String? = null): NFunctionInfo? {
        for ((index, pattern) in N_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                when (index) {
                    0 -> return NFunctionInfo(match.groupValues[1], match.groupValues[2].toIntOrNull(), isHardcoded = false)
                    1 -> return NFunctionInfo(match.groupValues[2], match.groupValues[3].toIntOrNull(), isHardcoded = false)
                    else -> {
                        if (pattern.toPattern().matcher("").groupCount() < 1) continue
                        return NFunctionInfo(match.groupValues[1], null, isHardcoded = false)
                    }
                }
            }
        }
        if (hasQArrayObfuscation(playerJs)) {
            val hashToUse = knownHash ?: extractPlayerHash(playerJs)
            if (hashToUse != null) {
                val config = getHardcodedConfig(hashToUse)
                if (config != null) {
                    return NFunctionInfo(config.nFuncName, config.nArrayIndex, config.nConstantArgs, isHardcoded = true)
                }
            }
        }
        return null
    }

    fun extractSignatureTimestamp(playerJs: String): Int? {
        val patterns = listOf(
            Regex("""signatureTimestamp['":\s]+(\d+)"""),
            Regex("""sts['":\s]+(\d+)"""),
            Regex(""""signatureTimestamp"\s*:\s*(\d+)""")
        )
        for ((index, pattern) in patterns.withIndex()) {
            val match = pattern.find(playerJs)
            if (match != null) {
                val sts = match.groupValues[1].toIntOrNull()
                if (sts != null) return sts
            }
        }
        val playerHash = extractPlayerHash(playerJs)
        if (playerHash != null) {
            val config = getHardcodedConfig(playerHash)
            if (config != null) return config.signatureTimestamp
        }
        return null
    }

    fun analyzePlayerJs(playerJs: String, knownHash: String? = null): PlayerAnalysis {
        val playerHash = knownHash ?: extractPlayerHash(playerJs)
        val hasQArray = hasQArrayObfuscation(playerJs)
        val sigInfo = extractSigFunctionInfo(playerJs, playerHash)
        val nFuncInfo = extractNFunctionInfo(playerJs, playerHash)
        val signatureTimestamp = extractSignatureTimestamp(playerJs)

        return PlayerAnalysis(
            playerHash = playerHash,
            hasQArrayObfuscation = hasQArray,
            sigInfo = sigInfo,
            nFuncInfo = nFuncInfo,
            signatureTimestamp = signatureTimestamp
        )
    }

    data class PlayerAnalysis(
        val playerHash: String?,
        val hasQArrayObfuscation: Boolean,
        val sigInfo: SigFunctionInfo?,
        val nFuncInfo: NFunctionInfo?,
        val signatureTimestamp: Int?
    )
}
