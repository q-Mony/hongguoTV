package com.hongguotv.core
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SignerTest {
    // The oracle vectors in signing.json were captured from the vendored drpys implementation, whose
    // medusa/helios payloads embed that implementation's device fingerprint. The shipped signer now
    // generates a per-install device_id/iid, so pin the original identity for this fidelity check:
    // it verifies the port still reproduces the reference bytes, not what identity we ship with.
    private val pinnedDevice = linkedMapOf(
        "iid" to "1905892595382586",
        "device_id" to "1905892595378490",
        "ac" to "wifi",
        "channel" to "update_64",
        "aid" to "8662",
        "app_name" to "novelread",
        "version_code" to "71332",
        "version_name" to "7.1.3.32",
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "device_type" to "25053RT47C",
        "device_brand" to "Redmi",
        "language" to "zh",
        "os_api" to "36",
        "os_version" to "16",
        "manifest_version_code" to "71332",
        "resolution" to "1280*2772",
        "dpi" to "520",
        "update_version_code" to "71332",
        "host_abi" to "arm64-v8a",
        "dragon_device_type" to "phone",
        "pv_player" to "71332",
        "compliance_status" to "0",
        "need_personal_recommend" to "1",
        "player_so_load" to "1",
        "is_android_pad_screen" to "0"
    )

    @Test fun matchesPinnedJavaScriptOracleAcrossTimestampVariants() {
        val rows = JSONArray(javaClass.getResource("/signing.json")!!.readText())
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i); val ts=row.getInt("ts"); val url=row.getString("url"); val query=url.substringAfter('?')
            val body=if(row.isNull("body")) null else row.getString("body").toByteArray()
            var seed=17L
            val signer=Signer({ts.toLong()*1000}, { seed=(seed*1664525+1013904223) and 0xffffffffL; seed/4294967296.0 }, { pinnedDevice })
            assertEquals("branch $i",0,signer.branch(query,body,ts))
            assertEquals("hash $i",row.getString("hash"),signer.hashF13(sm3(query.toByteArray()),body?.let{digest("MD5",it)}?:ByteArray(16),ts).hex())
            assertEquals("gorgon $i",row.getString("gorgon"),signer.gorgon(query,body,ts,42131))
            assertEquals("helios $i",row.getString("helios"),signer.helios(ts))
            assertEquals("medusa $i",row.getString("medusa"),signer.medusa(url,body,ts))
        }
    }

    // x-medusa is intentionally absent from signed requests. Measured on the player route, the device
    // fingerprint is what decides success (pinned fails with or without medusa; a fresh device works
    // with or without it). It is dropped because it embeds a device id of its own: a medusa that
    // contradicts the query's device_id makes the server answer HTTP 200 with an empty body, which is
    // indistinguishable from a real outage. This guards against reintroducing it.
    @Test fun signedRequestsOmitTheStaleMedusaHeader() {
        val signer=Signer({1800500000L*1000})
        val request=signer.sign("https://example.com/novel/player/multi_video_model/preload/v1/",linkedMapOf("aid" to "8662"),"{}".toByteArray())
        assertFalse("x-medusa must not be sent",request.headers.keys.any { it.equals("x-medusa",ignoreCase=true) })
        assertTrue("x-gorgon must still be sent",request.headers.keys.any { it.equals("x-gorgon",ignoreCase=true) })
    }

    // A device_id hardcoded in a public client is shared by every user of that client and gets
    // flagged upstream: the request then comes back as HTTP 200 with an empty body.
    @Test fun deviceIdentityIsGeneratedPerInstallation() {
        val deviceId=VendorConstants.device.getValue("device_id")
        val iid=VendorConstants.device.getValue("iid")
        assertTrue("device_id must look like a registered install",deviceId.matches(Regex("[0-9]{19}")))
        assertTrue("iid must look like a registered install",iid.matches(Regex("[0-9]{19}")))
        assertNotEquals("device_id and iid must differ",deviceId,iid)
        assertNotEquals("must not reuse the public pinned device_id","1905892595378490",deviceId)
        assertNotEquals("must not reuse the public pinned iid","1905892595382586",iid)
    }

    // The identity has to survive a restart. Regenerating it per launch would present a brand-new
    // device on every start, which is the device-farm pattern that gets silently throttled.
    @Test fun restoredIdentityIsReusedAndSurvivesRestart() {
        DeviceFingerprint.restore("1234567890123456789","9876543210987654321")
        val (deviceId,iid)=DeviceFingerprint.current()
        assertEquals("1234567890123456789",deviceId)
        assertEquals("9876543210987654321",iid)
        // A second read must return the same pair, not mint a new one.
        assertEquals(DeviceFingerprint.current(),DeviceFingerprint.current())
        // Signing must use the restored identity, not a fresh random one.
        val signed=Signer({1800500000L*1000}).sign("https://example.com/x",VendorConstants.device,null)
        assertTrue("signed URL must carry the restored device_id",signed.url.contains("device_id=1234567890123456789"))
        assertTrue("signed URL must carry the restored iid",signed.url.contains("iid=9876543210987654321"))
    }

    @Test fun malformedStoredIdentityFallsBackInsteadOfPropagating() {
        DeviceFingerprint.restore("not-a-device-id","")
        val (deviceId,iid)=DeviceFingerprint.current()
        assertTrue("a malformed stored id must not be adopted",deviceId.matches(Regex("[0-9]{19}")))
        assertTrue(iid.matches(Regex("[0-9]{19}")))
        assertNotEquals("not-a-device-id",deviceId)
    }
}
