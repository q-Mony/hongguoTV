// Port of yhoops/onedrama hongguo/sign.go (GPL-3.0; see NOTICE.md).
// Fallback signer: used only when the primary signer's requests are refused.
package com.hongguotv.core

import java.security.MessageDigest

/**
 * The minimal signing scheme the current upstream client uses: `X-Gorgon` / `X-Khronos` /
 * `X-SS-Req-Ticket` / `X-SS-STUB` and nothing else.
 *
 * This is the fallback. The primary [Signer] carries the full anti-bot header set and is preferred
 * because it is the shape this project has historically been validated with. If upstream ever stops
 * accepting that shape, this one is the escape hatch -- it was measured working against the live
 * player route on 2026-10-16 (see docs/research/2026-10-15-hongguo-play-api-recovery.md).
 *
 * Its gorgon is unrelated to [Signer.gorgon]: a fixed 20-byte key XOR followed by
 * rotate-left-4 / reverse-bits, emitted with an `8404 401c` prefix.
 */
internal class FallbackSigner(
    private val clock: () -> Long = System::currentTimeMillis,
    private val deviceProvider: () -> Map<String,String> = { VendorConstants.device },
    private val versionCode: String = "73532",
    private val versionName: String = "7.3.5.32"
) {
    private val device get() = deviceProvider()
    private val key = intArrayOf(0x44,0xb9,0xb9,0xd9,0xa4,0xae,0xf9,0xfc,0xa4,0x93,0xaa,0x75,0x7c,0xa3,0xc2,0xc4,0xa4,0x96,0x93,0x8f)

    private fun rotl8(value: Int,count: Int)=((value shl count) or (value ushr (8-count))) and 0xff
    private fun reverse8(value: Int): Int { var result=0; for(i in 0 until 8) result=(result shl 1) or ((value shr i) and 1); return result and 0xff }
    private fun md5(data: ByteArray)=MessageDigest.getInstance("MD5").digest(data)

    /** The query this scheme expects. Callers may add their own parameters on top. */
    fun query(): Map<String,String> = linkedMapOf(
        "aid" to "8662","app_name" to "novelread","version_code" to versionCode,"version_name" to versionName,
        "manifest_version_code" to versionCode,"update_version_code" to versionCode,"channel" to "update_64",
        "device_platform" to "android","os" to "android","ssmix" to "a","device_type" to "25053RT47C",
        "device_brand" to "Redmi","language" to "zh","os_api" to "36","os_version" to "16",
        "resolution" to "1280*2772","dpi" to "520","ac" to "wifi",
        "device_id" to device.getValue("device_id"),"iid" to device.getValue("iid")
    )

    fun sign(base: String, values: Map<String,String>, body: ByteArray? = null): Signer.Request {
        val now=clock(); val ts=(now/1000).toInt()
        // Caller-supplied parameters win over this scheme's defaults, so route-specific fields
        // (search cursors, paging) survive the switch to the fallback.
        val merged=query()+values
        val query=Signer.encode(merged + mapOf("_rticket" to now.toString()))
        val payload=ByteArray(20)
        md5(query.toByteArray()).copyInto(payload,0,0,4)
        val headers=linkedMapOf(
            "User-Agent" to VendorConstants.VIDEO_UA,
            "Accept" to "application/json",
            "X-XS-From-Web" to "0",
            "Sdk-Version" to "2"
        )
        if(body!=null) { val hash=md5(body); hash.copyInto(payload,4,0,4); headers["X-SS-STUB"]=hash.hex().uppercase() }
        byteArrayOf(0,6,11,28).copyInto(payload,12)
        payload[16]=(ts ushr 24).toByte(); payload[17]=(ts ushr 16).toByte(); payload[18]=(ts ushr 8).toByte(); payload[19]=ts.toByte()
        for(i in payload.indices) payload[i]=(payload[i].toInt() xor key[i]).toByte()
        for(i in payload.indices) {
            val mixed=rotl8(payload[i].toInt() and 0xff,4) xor (payload[(i+1)%payload.size].toInt() and 0xff)
            payload[i]=(reverse8(mixed) xor 0xff xor payload.size).toByte()
        }
        headers["X-Khronos"]=ts.toString()
        headers["X-Gorgon"]=(byteArrayOf(0x84.toByte(),0x04,0x40,0x1c,0,0)+payload).hex()
        headers["X-SS-Req-Ticket"]=now.toString()
        if(body!=null) headers["Content-Type"]="application/json; charset=utf-8"
        return Signer.Request("$base?$query",headers,body)
    }
}
