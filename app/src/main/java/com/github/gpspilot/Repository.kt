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
class Repository(private val _db: Database) {
    // TODO: remove unnecessary routes

    private val routesUpdates = BroadcastChannel<Unit>(1)

    private suspend inline fun <T> readRoutes(crossinline block: suspend RoutesDao.() -> T): T {
        return withContext(Dispatchers.IO) {
            _db.routes().block()
        }
    }

    private suspend inline fun <T> writeRoutes(crossinline block: suspend RoutesDao.() -> T): T {
        return withContext(Dispatchers.IO) {
            _db.routes().block().also {
                routesUpdates.offer()
            }
        }
    }

    private suspend inline fun <T> whenRoutesUpdated(crossinline block: suspend () -> T): ReceiveChannel<T> {
        return routesUpdates.openSubscription().startWith(coroutineContext, Unit).map { block() }
    }



    suspend fun getRouteList(): ReceiveChannel<List<Route>> {
        return whenRoutesUpdated {
            readRoutes {
                get(ROUTES_COUNT).map { it.fromEntity() }.apply {
                    i { "Loaded $size routes." }
                }
            }
        }
    }

    /**
     * Return route with [routeId] or `null` if route with this id doesn't exist.
     * [RouteEntity.lastOpened] will be updated to current time and saved to DB before return.
     */
    suspend fun openRoute(routeId: Id): Route? = writeRoutes {
        // Try to load route
        getById(routeId)?.let { entity ->
            // If loaded - update last opened time
            val newEntity = entity.copy(lastOpened = Date())
            // And insert updated object to DB
            insertOrReplace(newEntity)
            i { "Updated open time for route with id: $routeId (${newEntity.lastOpened})." }
            // Return updated object
            newEntity.fromEntity()
        }
    }

    suspend fun addRoute(route: UnsavedRoute) {
        writeRoutes {
            val lastOpened = Date() // For newly created routes it assumed it was opened recently
            val id = insertOrReplace(route.toEntity(lastOpened))
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

private fun UnsavedRoute.toEntity(lastOpened: Date) = RouteEntity(
    id = 0L,
    name = name,
    created = created,
    lastOpened = lastOpened,
    length = length,
    file = file
)