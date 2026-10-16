// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * The per-install device fingerprint used to sign requests.
 *
 * A device_id/iid pair that is hardcoded in a public client is shared by every user of that client
 * and is flagged upstream: signed requests then come back as HTTP 200 with an empty body, which is
 * indistinguishable from a real outage. The pair therefore has to be generated per installation and
 * kept for the life of that installation.
 *
 * It must be persisted, not regenerated per launch or per request: presenting a brand-new device on
 * every start looks like a device farm and gets throttled the same way. [restore] is the read side,
 * [current] the value to write back, and [rotateIfRejected] the only path that mints a replacement.
 */
object DeviceFingerprint {
    /**
     * How long an identity must be kept before a rejection may replace it.
     *
     * This is the guard that keeps a sustained upstream outage from churning identities: without it,
     * every launch during an outage would look like a rejection and mint a new device, which is
     * precisely the device-farm pattern that gets throttled. One rotation per day is enough to
     * recover from a genuinely burned identity while staying invisible during an outage.
     */
    const val MIN_ROTATION_INTERVAL_MILLIS = 24L*60*60*1000

    /** Called whenever the identity changes, so the app can persist it. */
    @Volatile var persist: ((String,String)->Unit)? = null

    private var lastRotatedAt: Long = 0

    /**
     * Adopt a previously stored identity. Null or malformed values fall back to a generated pair.
     *
     * [rotatedAt] is adopted as-is, including 0, which means "never rotated". Callers must therefore
     * pass the persisted stamp rather than omitting it, or a rotation would be forgotten on restart.
     */
    fun restore(deviceId: String?, installId: String?, rotatedAt: Long = 0) {
        VendorConstants.adopt(deviceId,installId)
        lastRotatedAt=rotatedAt
    }

    /** The identity in use, to persist so the next launch reuses it. */
    fun current(): Pair<String,String> {
        val device=VendorConstants.device
        return device.getValue("device_id") to device.getValue("iid")
    }

    fun lastRotatedAt(): Long = lastRotatedAt

    /**
     * Replace the identity, but only if upstream actually rejected it and the current one has been
     * held long enough to rule out an outage.
     *
     * Returns the new pair when it rotated, or null when it declined. Declining is the common case
     * and is not an error: a sustained outage must not be mistaken for a ban.
     */
    fun rotateIfRejected(now: Long = System.currentTimeMillis()): Pair<String,String>? {
        if(lastRotatedAt!=0L && now-lastRotatedAt<MIN_ROTATION_INTERVAL_MILLIS) return null
        val (deviceId,installId)=VendorConstants.newIdentity()
        VendorConstants.replace(deviceId,installId)
        lastRotatedAt=now
        persist?.invoke(deviceId,installId)
        return deviceId to installId
    }
}
