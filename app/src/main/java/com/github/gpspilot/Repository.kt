package com.github.gpspilot

import i
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import kotlin.coroutines.coroutineContext


const val ROUTES_COUNT = 10

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
class Repository(private val db: Database) {
    // TODO: remove unnecessary routes

    private val routesUpdates = BroadcastChannel<Unit>(1)

    suspend fun getRouteList(): ReceiveChannel<List<Route>> {
        return routesUpdates.openSubscription().startWith(coroutineContext, Unit).map { _ ->
            withContext(Dispatchers.IO) {
                db.routes().get(ROUTES_COUNT).map { it.fromEntity() }.apply {
                    i { "Loaded $size routes." }
                }
            }
        }
    }

    suspend fun addRoute(route: UnsavedRoute) {
        withContext(Dispatchers.IO) {
            val id = db.routes().insertOrReplace(route.toEntity())
            i { "New route inserted with id: $id." }
            routesUpdates.send()
        }
    }
}


typealias Route = RouteData<Id>
typealias UnsavedRoute = RouteData<Nothing?>

data class RouteData<T>(
    val id: T,
    val name: String,
    val created: Date,
    /**
     * Length in meters.
     */
    val length: Long,
    val file: File
)

private fun RouteEntity.fromEntity() = Route(
    id = id,
    name = name,
    created = created,
    length = length,
    file = file
)

private fun UnsavedRoute.toEntity() = RouteEntity(
    id = 0L,
    name = name,
    created = created,
    lastOpened = created, // For newly created routes it assumed it was opened recently
    length = length,
    file = file
)