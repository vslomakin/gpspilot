package com.github.gpspilot

import android.app.Application
import androidx.room.*
import androidx.room.OnConflictStrategy.REPLACE
import java.io.File
import java.util.*


private const val DB_FNAME = "database.db"

fun createDb(ctx: Application): Database = Room.databaseBuilder(ctx, Database::class.java, DB_FNAME).build()


@androidx.room.Database(
    entities = [
        RouteEntity::class
    ],
    version = 1
)
@TypeConverters(Converters::class)
abstract class Database : RoomDatabase() {
    abstract fun routes(): RoutesDao
}


typealias Id = Long


@Entity(
    tableName = "Routes",
    indices = [Index("created", unique = false)]
) data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Id,
    val name: String,
    val created: Date,
    val lastOpened: Date,
    val length: Long,
    val file: File
)

@Dao interface RoutesDao {
    @Query("SELECT * FROM Routes ORDER BY lastOpened ASC LIMIT :count")
    fun get(count: Int): List<RouteEntity>

    @Insert(onConflict = REPLACE)
    fun insertOrReplace(route: RouteEntity): Id
}




private class Converters {
    @TypeConverter fun fromDate(date: Date): Long = date.time
    @TypeConverter fun toDate(timestamp: Long): Date = Date(timestamp)

    @TypeConverter fun fromFile(file: File): String = file.path
    @TypeConverter fun toFile(path: String): File = File(path)
}