package tomate.data.sync

import tomate.core.model.Outcome

/**
 * Les trois appels PostgREST dont la file d'attente a besoin.
 *
 * Cette interface existe pour une raison précise : sans elle, la stratégie d'idempotence de
 * [SyncEngine] ne pouvait pas être vérifiée par un test JVM. Le vrai client l'implémente, un faux
 * client l'enregistre, et on peut donc prouver qu'un rejeu n'écrase pas et ne duplique pas.
 */
interface OutboxRemote {
    suspend fun insert(
        table: String,
        body: String,
        upsert: Boolean = false,
        onConflict: String? = null,
        representation: Boolean = true,
        ignoreDuplicates: Boolean = false,
    ): Outcome<String>

    suspend fun delete(table: String, filter: List<Pair<String, String>>): Outcome<String>

    suspend fun select(
        table: String,
        query: List<Pair<String, String>> = emptyList(),
        single: Boolean = false,
    ): Outcome<String>
}
