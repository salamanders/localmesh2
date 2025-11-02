package info.benjaminhill.localmesh2.p2p

import kotlin.random.Random


private const val BASE58_ALPHABET = "123456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ"

fun randomString(length: Int): String = buildString(length) {
    repeat(length) {
        val randomIndex = Random.Default.nextInt(BASE58_ALPHABET.length)
        append(BASE58_ALPHABET[randomIndex])
    }
}