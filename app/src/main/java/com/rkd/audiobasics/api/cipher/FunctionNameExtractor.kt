package com.rkd.audiobasics.api.cipher

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
        // Auto-discovered URL-class candidates (g.<name>) to be verified at runtime in the
        // WebView; the first one whose get('n') transform passes validation wins.
        val classCandidates: List<String>? = null,
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

    // ── Structural (format-tolerant) patterns for 2026+ players ──────────────
    // Modern sig call:  NAME(<ints...>,decodeURIComponent(x.s))  -> NAME(<ints...>,INPUT)
    // The number of leading int args has changed between rotations, so accept 1..4.
    private val SIG_EXPR_LEADING_ARGS = Regex(
        """\b([A-Za-z0-9${'$'}_]{1,8})\(\s*((?:\d+\s*,\s*){1,4})decodeURIComponent\s*\("""
    )
    // Same call with the signature first:  NAME(decodeURIComponent(x.s),<ints...>)
    private val SIG_EXPR_TRAILING_ARGS = Regex(
        """\b([A-Za-z0-9${'$'}_]{1,8})\(\s*decodeURIComponent\s*\([^)]*\)((?:\s*,\s*\d+){1,4})\s*\)"""
    )

    // URL-class constructors exported on the player namespace: g.XX=function(a,b){  /  g.XX=class
    private val URL_CLASS_CANDIDATE = Regex(
        """\bg\.([A-Za-z0-9${'$'}_]{1,4})\s*=\s*(?:function\s*\(\s*\w+\s*,\s*\w+\s*\)|class\b)"""
    )
    private const val MAX_CLASS_CANDIDATES = 300

    // yt-dlp/ejs anchors the URL-class function by a call with the literal args ("alr","yes")
    // inside its body (a stable marker across rotations). We use it to rank candidates.
    private val ALR_YES_ANCHOR = Regex("""\(\s*["']alr["']\s*,\s*["']yes["']\s*\)""")
    private const val ANCHOR_MAX_DISTANCE = 12_000

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

    // ── Structural discovery helpers ──────────────────────────────────────────

    internal fun discoverSigExpression(playerJs: String): String? {
        SIG_EXPR_LEADING_ARGS.find(playerJs)?.let { m ->
            val args = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (args.isNotEmpty()) return "${m.groupValues[1]}(${args.joinToString(",")},INPUT)"
        }
        SIG_EXPR_TRAILING_ARGS.find(playerJs)?.let { m ->
            val args = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (args.isNotEmpty()) return "${m.groupValues[1]}(INPUT,${args.joinToString(",")})"
        }
        return null
    }

    /**
     * Collects g.<Name> constructors that could be the URL class whose get('n') applies the
     * n-transform. Candidates whose body looks URL-ish (query-string handling) are ordered
     * first; the WebView verifies each one by actually running it, so a wrong guess costs a
     * skipped candidate, not a broken player.
     */
    internal fun discoverNClassCandidates(playerJs: String): List<String> {
        val defs = URL_CLASS_CANDIDATE.findAll(playerJs)
            .map { it.range.first to it.groupValues[1] }
            .toList()
        if (defs.isEmpty()) return emptyList()

        // 1) Anchored: nearest preceding g.X= definition for each ("alr","yes") call site.
        val anchored = LinkedHashSet<String>()
        for (a in ALR_YES_ANCHOR.findAll(playerJs)) {
            val pos = a.range.first
            var lo = 0
            var hi = defs.size - 1
            var best = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (defs[mid].first < pos) { best = mid; lo = mid + 1 } else hi = mid - 1
            }
            if (best >= 0 && pos - defs[best].first <= ANCHOR_MAX_DISTANCE) anchored += defs[best].second
        }

        // 2) Heuristic: URL-ish bodies next, everything else last. Runtime probe decides.
        val seen = LinkedHashSet<String>(anchored)
        val hinted = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for ((start, name) in defs) {
            if (!seen.add(name)) continue
            val body = playerJs.substring(start, minOf(playerJs.length, start + 1500))
            val urlish = body.contains("\"?\"") || body.contains("'?'") ||
                body.contains("\"&\"") || body.contains("searchParams") ||
                body.contains("\"=\"")
            if (urlish) hinted += name else rest += name
        }
        return (anchored.toList() + hinted + rest).take(MAX_CLASS_CANDIDATES)
    }

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

        // Structural discovery first (handles the 2026+ NAME(ints...,INPUT) shape)
        Timber.tag(TAG).w("No config for hash $hashToUse, trying structural sig discovery...")
        discoverSigExpression(playerJs)?.let { expr ->
            Timber.tag(TAG).d("SIG expression discovered structurally: $expr")
            return SigFunctionInfo(
                name = "_expr_sig",
                constantArg = null,
                jsExpression = expr,
                isHardcoded = false
            )
        }

        // Legacy regex fallback
        Timber.tag(TAG).w("Structural sig discovery found nothing, trying legacy sig regex patterns...")
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

        // Structural discovery first: candidate URL classes, verified at runtime in the WebView
        Timber.tag(TAG).w("No config for hash $hashToUse, trying structural n-class discovery...")
        val candidates = discoverNClassCandidates(playerJs)
        if (candidates.isNotEmpty()) {
            Timber.tag(TAG).d("N-class candidates: ${candidates.size} (first: ${candidates.take(5)})")
            return NFunctionInfo(
                name = "_auto_n",
                arrayIndex = null,
                classCandidates = candidates,
                isHardcoded = false
            )
        }

        // Legacy regex fallback
        Timber.tag(TAG).w("No n-class candidates, trying legacy n-func regex patterns...")
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
