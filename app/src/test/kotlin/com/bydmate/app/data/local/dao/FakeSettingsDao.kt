package com.bydmate.app.data.local.dao

import com.bydmate.app.data.local.entity.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [SettingsDao] for tests: a plain key -> value map, a null value is stored as "".
 * [observe] emits the value at subscription time only; [getAll] is always empty.
 */
class FakeSettingsDao(initial: Map<String, String> = emptyMap()) : SettingsDao {
    val map = mutableMapOf<String, String>().also { it.putAll(initial) }
    override suspend fun get(key: String): String? = map[key]
    override suspend fun getMany(keys: List<String>): List<SettingEntity> =
        keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
    override fun observe(key: String): Flow<String?> = flowOf(map[key])
    override suspend fun set(setting: SettingEntity) { map[setting.key] = setting.value ?: "" }
    override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
    override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
}
