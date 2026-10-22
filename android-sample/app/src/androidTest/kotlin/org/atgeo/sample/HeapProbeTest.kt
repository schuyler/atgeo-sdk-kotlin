package org.atgeo.sample

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// Throwaway: the JVM heap probe on ART, same 8 Manchester tiles. Not for main.
@RunWith(AndroidJUnit4::class)
class HeapProbeTest {
    private fun log(s: String) = Log.i("PROBE", s)

    private fun used(): Long {
        val rt = Runtime.getRuntime()
        repeat(6) { rt.gc(); System.runFinalization(); Thread.sleep(100) }
        return rt.totalMemory() - rt.freeMemory()
    }

    // Mirrors readTilePayload: keep each record's value, uri and importance.
    private class Rec(val value: JsonObject, val uri: String?, val importance: Long?)
    private fun read(body: JsonElement): List<Rec> =
        ((body as JsonObject)["records"] as JsonArray).mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            val v = r["value"] as? JsonObject ?: return@mapNotNull null
            Rec(v, (r["uri"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                (r["importance"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull()?.toLong())
        }
    private fun parse(b: ByteArray) = read(Json.parseToJsonElement(b.decodeToString()))

    private val pool = HashMap<String, String>()
    private fun internKeys(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.entries.associate { (k, v) -> pool.getOrPut(k) { k } to internKeys(v) })
        is JsonArray -> JsonArray(e.map(::internKeys))
        else -> e
    }
    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test
    fun probe() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bodies = assets.list("tiles")!!.sorted().map { n -> GZIPInputStream(assets.open("tiles/$n")).use { it.readBytes() } }
        val bytes = bodies.sumOf { it.size.toLong() }
        val records = bodies.sumOf { parse(it).size }

        val held = ArrayList<Any>()
        fun measure(make: () -> Any): Long { val b = used(); held += make(); return used() - b }
        val raw = measure { bodies.map { it.copyOf() } }
        val gzipped = measure { bodies.map { gz(it) } }
        val parsed = measure { bodies.map { parse(it) } }
        val interned = measure { bodies.map { read(internKeys(Json.parseToJsonElement(it.decodeToString()))) } }
        val parsedAgain = measure { bodies.map { parse(it) } }

        repeat(10) { bodies.forEach { parse(it) } }
        val times = (1..15).map { val t = System.nanoTime(); bodies.forEach { parse(it) }; (System.nanoTime() - t) / 1e6 }.sorted()
        log("api=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.first()} vm=${System.getProperty("java.vm.version")} maxMemory=${Runtime.getRuntime().maxMemory()} held=${held.size}")
        log("tiles=${bodies.size} records=$records bodyBytes=$bytes")
        log("heap raw=$raw gzipped=$gzipped parsed=$parsed internedKeys=$interned parsedAgain=$parsedAgain")
        log("ratio parsed=%.2f parsedAgain=%.2f interned=%.2f raw=%.2f gz=%.3f".format(
            parsed.toDouble() / bytes, parsedAgain.toDouble() / bytes, interned.toDouble() / bytes, raw.toDouble() / bytes, gzipped.toDouble() / bytes))
        log("parse all tiles ms median=%.1f min=%.1f max=%.1f".format(times[7], times[0], times.last()))
    }
}
