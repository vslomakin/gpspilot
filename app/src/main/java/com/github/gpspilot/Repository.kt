package com.github.gpspilot

import i
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.map
import w
import java.io.File
import java.util.*
import kotlin.coroutines.coroutineContext


const val ROUTES_COUNT = 10

@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
class Repository(
    /**
     * Should not be called directly. Use [readRoutes] or [writeRoutes].
     */
    private val _db: Database
) {

    /**
     * Needed to be aware when routes are updates.
     */
    private val routesUpdates = BroadcastChannel<Unit>(1)

    /**
     * Should be used for read operation.
     */
    private suspend inline fun <T> readRoutes(crossinline block: suspend RoutesDao.() -> T): T {
        return withContext(Dispatchers.IO) {
            _db.routes().block()
        }
    }

    /**
     * Should be used for write (write and read) operations.
     */
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


    /**
     * Send lists of [Route]. Sends new list when routes are updated.
     */
    suspend fun getRouteList(): ReceiveChannel<List<Route>> {
        return whenRoutesUpdated {
            readRoutes {
                get(limit = ROUTES_COUNT).map { it.fromEntity() }.apply {
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

    fun CoroutineScope.removeUnnecessaryRoutes() = launch(Dispatchers.IO) {
        val routes = readRoutes { get(offset = ROUTES_COUNT) }
        if (routes.isNotEmpty()) {
            writeRoutes {
                val deleted = delete(routes.first().lastOpened)
                i { "Deleted $deleted routes from DB." }
            }
            routes.forEach { route ->
                val deleted = route.file.delete()
                if (deleted) i { "Route ${route.file.path} deleted." }
                else w { "Route ${route.file.path} hasn't been deleted!" }
            }
        } else {
            i { "There's no unnecessary routes4." }
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