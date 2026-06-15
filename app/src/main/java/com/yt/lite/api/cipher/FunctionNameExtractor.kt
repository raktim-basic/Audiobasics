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
        // e.g. "(function(n){try{var u=new g.Yx(...)}catch(e){return n;}})(INPUT)"
        val jsExpression: String? = null,
        val isHardcoded: Boolean = false
    )

    data class HardcodedPlayerConfig(
        val sigFuncName: String,
        val sigConstantArg: Int?,
        val sigConstantArgs: List<Int>? = null,
        val sigPreprocessFunc: String? = null,
        val sigPreprocessArgs: List<Int>? = null,
        // JS expression for modern VM-dispatch players (replaces sigFuncName)
        val sigJsExpression: String? = null,
        val nFuncName: String,
        val nArrayIndex: Int?,
        val nConstantArgs: List<Int>?,
        // JS expression for modern n-transform (replaces nFuncName)
        val nJsExpression: String? = null,
        val signatureTimestamp: Int
    )

    // ── Known player configs ──────────────────────────────────────────────────
    // Each entry covers a specific player.js hash (real URL hash + MD5 fallback).
    // Modern players (2026+) use VM-dispatch expressions instead of named functions.
    // When regex extraction fails (Q-array obfuscation), we fall back to these.

    private val KNOWN_PLAYER_CONFIGS = mapOf(

        // March 2026
        "74edf1a3" to HardcodedPlayerConfig(
            sigFuncName = "JI", sigConstantArg = 48,
            sigConstantArgs = listOf(48, 1918),
            sigPreprocessFunc = "f1", sigPreprocessArgs = listOf(1, 6528),
            nFuncName = "GU", nArrayIndex = null,
            nConstantArgs = listOf(6, 6010),
            signatureTimestamp = 20522
        ),

        // April 2026
        "f4c47414" to HardcodedPlayerConfig(
            sigFuncName = "hJ", sigConstantArg = 6,
            sigConstantArgs = listOf(6),
            nFuncName = "", nArrayIndex = null, nConstantArgs = null,
            signatureTimestamp = 20543
        ),

        // May 2026 — direct URLs, no cipher or n-transform needed
        "57f5d44f" to HardcodedPlayerConfig(
            sigFuncName = "", sigConstantArg = null,
            nFuncName = "", nArrayIndex = null, nConstantArgs = null,
            signatureTimestamp = 20591
        ),

        // 2026-06-08 — VM-dispatch via Jf/C6/iE. STS 20611.
        "69e2a55d" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "Jf(20,3699,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.iE('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20611
        ),
        // MD5 fallback for 69e2a55d
        "70d8066f" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "Jf(20,3699,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.iE('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20611
        ),

        // 2026-06-08 — VM-dispatch via v0/n7/uY. STS 20607.
        "9d2ef9ef" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "v0(35,4499,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.uY('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20607
        ),
        // MD5 fallback for 9d2ef9ef
        "6fb43da5" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "v0(35,4499,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.uY('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20607
        ),

        // 2026-06-09 — VM-dispatch via mP/Yx. STS 20613. ← CURRENT hash 445213fb maps here
        "16ee6936" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "mP(4,155,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.Yx('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20613
        ),
        // MD5 fallback for 16ee6936 — this is what hash 445213fb computes to
        "ca366632" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "mP(4,155,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.Yx('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20613
        ),

        // 2026-06-09 — VM-dispatch via $9/cV. STS 20612.
        "ce74690f" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "\$9(2,6487,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.cV('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20612
        ),
        // MD5 fallback for ce74690f
        "a5669e32" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "\$9(2,6487,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.cV('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20612
        ),

        // 445213fb — the actual URL hash YouTube is currently serving (June 2026).
        // Same player generation as 16ee6936/ca366632: STS 20613, length 2785264.
        // Verified: Metrolist plays songs with this player using mP(4,155,INPUT)/Yx.
        "445213fb" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "mP(4,155,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.Yx('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20613
        ),

        // 2026-06-10 — mP/Yx generation under new URL hash. STS 20613.
        "6b8eecd5" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "mP(4,155,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.Yx('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20613
        ),
        // MD5 fallback for 6b8eecd5
        "6ea478fa" to HardcodedPlayerConfig(
            sigFuncName = "_expr_sig", sigConstantArg = null,
            sigJsExpression = "mP(4,155,INPUT)",
            nFuncName = "_expr_n", nArrayIndex = null, nConstantArgs = null,
            nJsExpression = "(function(n){try{var u=new g.Yx('https://x.googlevideo.com/videoplayback?n='+n,true);var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)",
            signatureTimestamp = 20613
        ),
    )

    // ── Detection patterns ────────────────────────────────────────────────────

    private val Q_ARRAY_PATTERN = Regex("""var\s+Q\s*=\s*"[^"]+"\s*\.\s*split\s*\(\s*"\}"\s*\)""")

    private val PLAYER_HASH_PATTERNS = listOf(
        Regex("""jsUrl['"\s:]+[^"']*?/player/([a-f0-9]{8})/"""),
        Regex("""player_ias\.vflset/[^/]+/([a-f0-9]{8})/"""),
        Regex("""/s/player/([a-f0-9]{8})/""")
    )

    private val SIG_FUNCTION_PATTERNS = listOf(
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\)"""),
        Regex("""&&\s*\(\s*[a-zA-Z0-9$]+\s*=\s*([a-zA-Z0-9$]+)\s*\(\s*(\d+)\s*,\s*decodeURIComponent\s*\(\s*[a-zA-Z0-9$]+\s*\.\s*[a-z]\s*\)"""),
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
        // Fixed: was "hasQArray=\$hasQArray" (literal) — now correctly interpolates
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
        val config = KNOWN_PLAYER_CONFIGS[playerHash]
        if (config == null) {
            Timber.tag(TAG).w("No hardcoded config for hash: $playerHash")
            Timber.tag(TAG).w("Known hashes: ${KNOWN_PLAYER_CONFIGS.keys.joinToString()}")
        }
        return config
    }

    fun extractSigFunctionInfo(playerJs: String, knownHash: String? = null): SigFunctionInfo? {
        // Try regex patterns first
        for ((index, pattern) in SIG_FUNCTION_PATTERNS.withIndex()) {
            val match = pattern.find(playerJs) ?: continue
            val name = match.groupValues[1]
            val constArg = if (match.groupValues.size > 2) match.groupValues[2].toIntOrNull() else null
            Timber.tag(TAG).d("SIG FUNCTION found via pattern $index: name=$name constantArg=$constArg")
            return SigFunctionInfo(name, constArg, isHardcoded = false)
        }

        // Fall back to hardcoded config (Q-array obfuscation or unknown pattern)
        val hashToUse = knownHash ?: extractPlayerHash(playerJs)
        Timber.tag(TAG).w("No sig pattern matched, trying hardcoded config for hash=$hashToUse")
        if (hashToUse != null) {
            val config = getHardcodedConfig(hashToUse)
            if (config != null) {
                Timber.tag(TAG).d("Using hardcoded sig: func=${config.sigFuncName} expr=${config.sigJsExpression}")
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

        Timber.tag(TAG).e("Could not extract signature function info from player JS")
        return null
    }

    fun extractNFunctionInfo(playerJs: String, knownHash: String? = null): NFunctionInfo? {
        // Try regex patterns first
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

        // Fall back to hardcoded config
        val hashToUse = knownHash ?: extractPlayerHash(playerJs)
        Timber.tag(TAG).w("No n-func pattern matched, trying hardcoded config for hash=$hashToUse")
        if (hashToUse != null) {
            val config = getHardcodedConfig(hashToUse)
            if (config != null) {
                Timber.tag(TAG).d("Using hardcoded n-func: func=${config.nFuncName} expr=${config.nJsExpression?.take(60)}")
                return NFunctionInfo(
                    name = config.nFuncName,
                    arrayIndex = config.nArrayIndex,
                    constantArgs = config.nConstantArgs,
                    jsExpression = config.nJsExpression,
                    isHardcoded = true
                )
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
        // Fallback to hardcoded config
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
