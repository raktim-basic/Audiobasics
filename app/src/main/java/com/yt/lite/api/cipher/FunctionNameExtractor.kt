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
        // Expression-based sig deobfuscation (modern players, 2026+)
        // e.g. "mP(4,155,INPUT)" where INPUT is replaced with the obfuscated sig
        val jsExpression: String? = null,
        val isHardcoded: Boolean = false
    )

    data class NFunctionInfo(
        val name: String,
        val arrayIndex: Int?,
        val constantArgs: List<Int>? = null,
        // Expression-based n-transform (modern players, 2026+)
        val jsExpression: String? = null,
        val isHardcoded: Boolean = false
    )

    data class HardcodedPlayerConfig(
        val sigFuncName: String,
        val sigConstantArg: Int?,
        val sigConstantArgs: List<Int>? = null,
        val sigPreprocessFunc: String? = null,
        val sigPreprocessArgs: List<Int>? = null,
        val sigJsExpression: String? = null,
        val nFuncName: String,
        val nArrayIndex: Int?,
        val nConstantArgs: List<Int>?,
        val nJsExpression: String? = null,
        val signatureTimestamp: Int
    )

    // ── Detection patterns ────────────────────────────────────────────────────

    private val Q_ARRAY_PATTERN = Regex("""var\s+Q\s*=\s*"[^"]+"\s*\.\s*split\s*\(\s*"\}"\s*\)""")

    private val PLAYER_HASH_PATTERNS = listOf(
        Regex("""jsUrl['"\s:]+[^"']*?/player/([a-f0-9]{8})/"""),
        Regex("""player_ias\.vflset/[^/]+/([a-f0-9]{8})/"""),
        Regex("""/s/player/([a-f0-9]{8})/""")
    )

    private val SIG_FUNCTION_PATTERNS = listOf(
        // Pattern 1 (2025+): &&(VAR=FUNC(NUM,decodeURIComponent(VAR))
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\)"""),
        // Pattern 1a (April 2026): &&(z=hJ(6,decodeURIComponent(h.s))
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\.\s*[a-z]\s*\)"""),
        // Classic patterns (pre-2025, kept as fallback)
        Regex("""\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\b[a-zA-Z0-9]+\s*&&\s*[a-zA-Z0-9]+\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
        Regex("""\bm=([a-zA-Z0-9${'$'}]{2,})\(decodeURIComponent\(h\.s\)\)"""),
        Regex("""\bc\s*&&\s*d\.set\([^,]+\s*,\s*(?:encodeURIComponent\s*\()([a-zA-Z0-9$]+)\("""),
        Regex("""\bc\s*&&\s*[a-z]\.set\([^,]+\s*,\s*encodeURIComponent\(([a-zA-Z0-9$]+)\("""),
    )

    private val N_FUNCTION_PATTERNS = listOf(
        Regex("""\.get\("n"\)\)&&\(b=([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(([a-zA-Z0-9])\)"""),
        Regex("""\.get\("n"\)\)\s*&&\s*\(([a-zA-Z0-9$]+)\s*=\s*([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(\1\)"""),
        Regex("""\.get\("n"\);if\([a-zA-Z0-9$]+\)\s*\{[^}]*match"""),
        Regex("""\(\s*([a-zA-Z0-9$]+)\s*=\s*String\.fromCharCode\(110\)"""),
        Regex("""([a-zA-Z0-9$]+)\s*=\s*function\([a-zA-Z0-9]\)\s*\{[^}]*?enhanced_except_"""),
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun hasQArrayObfuscation(playerJs: String): Boolean {
        val hasQArray = Q_ARRAY_PATTERN.containsMatchIn(playerJs)
        Timber.tag(TAG).d("Q-array obfuscation check: hasQArray=$hasQArray")
        if (hasQArray) {
            val match = Q_ARRAY_PATTERN.find(playerJs)
            if (match != null) {
                val start = match.range.first
                val qDefEnd = playerJs.indexOf(";", start)
                if (qDefEnd > start) {
                    val qDef = playerJs.substring(start, qDefEnd)
                    val elementCount = qDef.count { it == '}' } + 1
                    Timber.tag(TAG).d("Q-array detected with ~$elementCount elements")
                }
            }
        }
        return hasQArray
    }

    fun extractPlayerHash(playerJs: String): String? {
        for (pattern in PLAYER_HASH_PATTERNS) {
            val match = pattern.find(playerJs)
            if (match != null) return match.groupValues[1]
        }
        // Fallback: compute MD5 of first 10KB
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(playerJs.take(10000).toByteArray())
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun getHardcodedConfig(playerHash: String): HardcodedPlayerConfig? {
        // Delegate entirely to PlayerConfigStore — no local map needed
        val config = PlayerConfigStore.get(playerHash)
        if (config == null) {
            Timber.tag(TAG).w("No hardcoded config for hash: $playerHash")
            Timber.tag(TAG).w("Known hashes: ${PlayerConfigStore.knownHashes().sorted().joinToString()}")
        }
        return config
    }

    /**
     * Extract signature function info.
     * Validated config FIRST (via PlayerConfigStore), regex heuristics only as fallback.
     */
    fun extractSigFunctionInfo(playerJs: String, knownHash: String? = null): SigFunctionInfo? {
        val hashToUse = knownHash ?: extractPlayerHash(playerJs)
        Timber.tag(TAG).d("Extracting sig info, hash=$hashToUse")

        // Config store first — prevents regex false-positives shadowing a known config
        if (hashToUse != null) {
            val config = getHardcodedConfig(hashToUse)
            if (config != null) {
                if (config.sigJsExpression != null) {
                    Timber.tag(TAG).d("USING EXPRESSION-BASED SIG: ${config.sigJsExpression}")
                } else {
                    Timber.tag(TAG).d("USING HARDCODED SIG FUNCTION: ${config.sigFuncName}")
                }
                return SigFunctionInfo(
                    name = config.sigFuncName,
                    constantArg = config.sigConstantArg,
                    constantArgs = config.sigConstantArgs,
                    preprocessFunc = config.sigPreprocessFunc,
                    preprocessArgs = config.sigPreprocessArgs,
                    jsExpression = config.sigJsExpression,
                    isHardcoded = true
                )
            }
        }

        // Regex fallback for unknown players
        Timber.tag(TAG).w("No config for hash $hashToUse, trying sig regex patterns...")
        for ((index, pattern) in SIG_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs) ?: continue
            val name = match.groupValues[1]
            val constArg = if (match.groupValues.size > 2) match.groupValues[2].toIntOrNull() else null
            Timber.tag(TAG).d("SIG FUNCTION found via pattern $index: name=$name constantArg=$constArg")
            return SigFunctionInfo(name, constArg, isHardcoded = false)
        }

        Timber.tag(TAG).e("Could not extract signature function info from player JS")
        return null
    }

    /**
     * Extract N-transform function info.
     * Validated config FIRST (via PlayerConfigStore), regex heuristics only as fallback.
     */
    fun extractNFunctionInfo(playerJs: String, knownHash: String? = null): NFunctionInfo? {
        val hashToUse = knownHash ?: extractPlayerHash(playerJs)
        Timber.tag(TAG).d("Extracting n-func info, hash=$hashToUse")

        // Config store first
        if (hashToUse != null) {
            val config = getHardcodedConfig(hashToUse)
            if (config != null) {
                if (config.nJsExpression != null) {
                    Timber.tag(TAG).d("USING EXPRESSION-BASED N-FUNCTION: ${config.nJsExpression.take(60)}")
                } else {
                    Timber.tag(TAG).d("USING HARDCODED N-FUNCTION: ${config.nFuncName}[${config.nArrayIndex}]")
                }
                return NFunctionInfo(
                    name = config.nFuncName,
                    arrayIndex = config.nArrayIndex,
                    constantArgs = config.nConstantArgs,
                    jsExpression = config.nJsExpression,
                    isHardcoded = true
                )
            }
        }

        // Regex fallback for unknown players
        Timber.tag(TAG).w("No config for hash $hashToUse, trying n-func regex patterns...")
        for ((index, pattern) in N_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs) ?: continue
            when (index) {
                0 -> {
                    Timber.tag(TAG).d("N-FUNCTION found via pattern $index")
                    return NFunctionInfo(match.groupValues[1], match.groupValues[2].toIntOrNull(), isHardcoded = false)
                }
                1 -> {
                    Timber.tag(TAG).d("N-FUNCTION found via pattern $index")
                    return NFunctionInfo(match.groupValues[2], match.groupValues[3].toIntOrNull(), isHardcoded = false)
                }
                else -> {
                    if (pattern.toPattern().matcher("").groupCount() < 1) continue
                    Timber.tag(TAG).d("N-FUNCTION found via pattern $index")
                    return NFunctionInfo(match.groupValues[1], null, isHardcoded = false)
                }
            }
        }

        Timber.tag(TAG).e("Could not extract n-function info from player JS")
        return null
    }

    fun extractSignatureTimestamp(playerJs: String): Int? {
        val patterns = listOf(
            Regex("""signatureTimestamp['"\s:]+(\d+)"""),
            Regex("""sts['"\s:]+(\d+)"""),
            Regex(""""signatureTimestamp"\s*:\s*(\d+)""")
        )
        for (pattern in patterns) {
            val sts = pattern.find(playerJs)?.groupValues?.get(1)?.toIntOrNull()
            if (sts != null) return sts
        }
        // Fallback to config store
        val hash = extractPlayerHash(playerJs)
        if (hash != null) {
            val config = getHardcodedConfig(hash)
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
        return PlayerAnalysis(playerHash, hasQArray, sigInfo, nFuncInfo, signatureTimestamp)
    }

    data class PlayerAnalysis(
        val playerHash: String?,
        val hasQArrayObfuscation: Boolean,
        val sigInfo: SigFunctionInfo?,
        val nFuncInfo: NFunctionInfo?,
        val signatureTimestamp: Int?
    )
}
