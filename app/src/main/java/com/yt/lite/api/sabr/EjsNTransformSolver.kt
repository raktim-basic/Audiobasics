package com.yt.lite.api.sabr

import android.content.Context
import android.util.Log
import app.cash.quickjs.QuickJs
import com.yt.lite.api.cipher.FunctionNameExtractor
import com.yt.lite.api.cipher.PlayerJsFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EjsNTransformSolver {
    private const val TAG = "EjsNTransformSolver"

    suspend fun solveN(context: Context, nParam: String, videoId: String): String? = withContext(Dispatchers.IO) {
        try {
            val playerJs = PlayerJsFetcher.fetchPlayerJs(videoId) ?: return@withContext null
            val functionName = FunctionNameExtractor.extractNFunctionName(playerJs) ?: return@withContext null

            QuickJs.create().use { quickJs ->
                quickJs.evaluate(context.assets.open("solver/meriyah.js").bufferedReader().readText())
                quickJs.evaluate(context.assets.open("solver/astring.js").bufferedReader().readText())
                quickJs.evaluate(context.assets.open("solver/yt.solver.core.js").bufferedReader().readText())

                val safeJs = playerJs.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
                quickJs.evaluate("var baseJsContent = '$safeJs';")

                val script = "extractEjsNTransform(baseJsContent, '$functionName')('$nParam')"
                return@use quickJs.evaluate(script) as? String
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sabr QuickJS failed to solve N parameter", e)
            null
        }
    }
}
