package com.dedovmosol.iwomail.data.database

import android.content.ContentValues
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * БД ровно по экспортированной схеме Room нужной версии (замороженная копия в test resources:
 * app/schemas в .gitignore, в CI генерируется только текущая версия) + одна строка accounts.
 * DRY для тестов миграций.
 */
internal object MigrationTestSupport {

    /**
     * Создаёт БД [dbName] версии [version] по схеме [schemaResource] и вставляет аккаунт: NOT NULL колонки —
     * нейтральными значениями, поверх — [accountOverrides]. Возвращает id аккаунта.
     */
    fun createDatabaseWithAccount(
        context: Context,
        dbName: String,
        schemaResource: String,
        version: Int,
        accountOverrides: ContentValues = ContentValues()
    ): Long {
        val json = requireNotNull(javaClass.getResource(schemaResource)) { "missing test resource $schemaResource" }.readText()
        val schema = JSONObject(json).getJSONObject("database")
        require(schema.getInt("version") == version) { "$schemaResource is not version $version" }
        val db = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        try {
            val entities = schema.getJSONArray("entities")
            var accountValues: ContentValues? = null
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    db.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                if (table == "accounts") accountValues = neutralRow(entity.getJSONArray("fields"))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = version
            val values = requireNotNull(accountValues) { "no accounts table in $schemaResource" }.apply {
                remove("id")
                putAll(accountOverrides)
            }
            return db.insertOrThrow("accounts", null, values)
        } finally {
            db.close()
        }
    }

    private fun neutralRow(fields: JSONArray): ContentValues = ContentValues().apply {
        for (i in 0 until fields.length()) {
            val field = fields.getJSONObject(i)
            if (!field.optBoolean("notNull")) continue
            val column = field.getString("columnName")
            when (field.getString("affinity")) {
                "INTEGER" -> put(column, 0L)
                "REAL" -> put(column, 0.0)
                "BLOB" -> put(column, ByteArray(0))
                else -> put(column, "")
            }
        }
    }
}
