package org.atgeo.sample

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.atgeo.client.AtgeoClient
import org.atgeo.client.Region

/** Searches for pizza near Manchester, New Hampshire, and lists what it finds. */
class MainActivity : Activity() {
    private val scope: CoroutineScope = MainScope()

    // One client per server for the life of the process, in a real app.
    private val atgeo = AtgeoClient(transport = OkHttpTransport())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { text = "Searching…" }
        setContentView(view)
        // Called from the main thread; the client parses and ranks on Dispatchers.Default.
        scope.launch {
            view.text = try {
                val result = atgeo.searchPlaces(Region.Circle(43.0, -71.45, 3000.0), "overturemaps.org", text = "pizza", collection = "place")
                result.places.take(20).joinToString("\n") { hit ->
                    val name = hit.name ?: "(unnamed)"
                    "%s, %.0f m, score %.2f".format(name, hit.distance, hit.score ?: 0.0)
                }.ifEmpty { "Nothing found." }
            } catch (e: CancellationException) {
                throw e // the activity is gone; there is no view to update
            } catch (e: Exception) {
                "Search failed: $e"
            }
        }
    }

    // Cancelling the scope cancels the search and, through OkHttp, its requests.
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
