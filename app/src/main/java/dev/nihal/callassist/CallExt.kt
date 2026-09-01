package dev.nihal.callassist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call

fun Call.stateCompat(): Int =
    if (Build.VERSION.SDK_INT >= 31) details.state else @Suppress("DEPRECATION") state

fun stateName(state: Int): String = when (state) {
    Call.STATE_RINGING -> "Incoming call"
    Call.STATE_DIALING -> "Dialing…"
    Call.STATE_CONNECTING -> "Connecting…"
    Call.STATE_ACTIVE -> "In call"
    Call.STATE_HOLDING -> "On hold"
    Call.STATE_DISCONNECTED -> "Call ended"
    Call.STATE_DISCONNECTING -> "Ending…"
    else -> "…"
}

data class CallerInfo(val name: String?, val photoUri: Uri?)

object ContactHelper {
    fun lookup(ctx: Context, number: String?): CallerInfo {
        if (number.isNullOrBlank()) return CallerInfo(null, null)
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return CallerInfo(null, null)
        }
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI
                ),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    return CallerInfo(c.getString(0), c.getString(1)?.let { Uri.parse(it) })
                }
            }
        } catch (_: Exception) {
        }
        return CallerInfo(null, null)
    }

    fun lookupName(ctx: Context, number: String?): String? = lookup(ctx, number).name

    /**
     * Decodes the contact photo for use as a notification icon, circle-cropped
     * to match the avatars elsewhere. Blocking — call off the main thread.
     */
    fun loadPhoto(ctx: Context, photoUri: Uri?): android.graphics.Bitmap? {
        if (photoUri == null) return null
        return try {
            ctx.contentResolver.openInputStream(photoUri)
                ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                ?.let { Ui.circleCrop(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** Lookup URI for the contact owning this number, for ACTION_VIEW. */
    fun lookupContactUri(ctx: Context, number: String?): Uri? {
        if (number.isNullOrBlank()) return null
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
        )
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    return ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1))
                }
            }
        } catch (_: Exception) {
        }
        return null
    }
}
