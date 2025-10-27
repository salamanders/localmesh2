package info.benjaminhill.localmesh2

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID

object CachedPrefs {

    private const val PREF_FILE_NAME = "device_id_prefs"
    private const val PREF_KEY_ID = "install_uuid"
    private const val TAG = "CachedPrefs"

    @Volatile
    private var uuid: String? = null

    @Synchronized
    fun getId(context: Context): String {
        if (uuid != null) {
            return uuid!!
        }

        val prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        val storedId = prefs.getString(PREF_KEY_ID, null)

        if (storedId != null) {
            Log.d(TAG, "Found stored ID: $storedId")
            uuid = storedId
            return storedId
        }

        val newId = getCharHash(UUID.randomUUID().toString(), 5)
        Log.w(TAG, "No stored ID found. Generating new ID: $newId")
        prefs.edit { putString(PREF_KEY_ID, newId) }
        uuid = newId
        return newId
    }


    /**
     * Generates a N-character alphanumeric (A-Za-z0-9) hash from an input string.
     *
     * @param input The string to hash (e.g., Settings.Secure.ANDROID_ID).
     * @return A short Base62 string.
     *
     * input could be Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)
     */
    fun getCharHash(input: String, length: Int = 6): String {
        val base62Chars =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray()
        val base = BigInteger.valueOf(base62Chars.size.toLong())

        val md5 = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        val bigInt = BigInteger(1, md5)

        val base62Sequence = generateSequence(bigInt) {
            (it / base).takeIf { quotient -> quotient > BigInteger.ZERO }
        }.map { number ->
            base62Chars[(number % base).toInt()]
        }

        return base62Sequence.joinToString("").reversed()
            .padStart(length, '0')
            .take(length)
    }
}