package app.core.services.attribution

import app.core.services.attribution.model.AttributionConsent
import app.core.services.attribution.model.AttributionInstallRequestBody
import app.core.services.attribution.model.AttributionInstallResponse
import app.core.services.attribution.model.ConsentRequestBody
import app.core.services.attribution.model.toAttributionConsent
import app.core.services.consent.Consent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the same [Json] settings as `AttributionServerApiImpl`. */
class AttributionInstallSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    @Test
    fun `response with null isAB decodes as null`() {
        val response = json.decodeFromString<AttributionInstallResponse>(
            """{"userId":"u1","userIp":"1.2.3.4","isAB":null}"""
        )

        assertNull(response.isAB)
    }

    @Test
    fun `response with isAB true decodes as true`() {
        val response = json.decodeFromString<AttributionInstallResponse>(
            """{"userId":"u1","userIp":null,"isAB":true}"""
        )

        assertEquals(true, response.isAB)
    }

    private fun installRequest(consent: AttributionConsent? = null) = AttributionInstallRequestBody(
        sdkVersion = "1.6.1",
        osVersion = "14",
        appVersion = "1.3.6",
        appVersionCode = 172L,
        limitAdTracking = false,
        advertisingId = "ad-id",
        appsflyerId = "af-uid",
        storeCountry = "US",
        environment = "Release",
        deviceId = "device-id",
        externalAuthorization = false,
        installParams = null,
        firebaseAppInstanceId = null,
        consent = consent,
    )

    private fun encode(request: AttributionInstallRequestBody) =
        json.parseToJsonElement(json.encodeToString(request)).jsonObject

    @Test
    fun `install without consent has no consent key`() {
        assertFalse("consent" in encode(installRequest()))
    }

    @Test
    fun `install consent carries the answer and gdprApplies`() {
        val consent = Consent.DENIED_ALL.copy(adUserData = true, isUserSubjectToGDPR = true)

        val encoded = encode(installRequest(consent.toAttributionConsent())).getValue("consent").jsonObject

        assertEquals(true, encoded.getValue("gdprApplies").jsonPrimitive.boolean)
        assertEquals(true, encoded.getValue("adUserData").jsonPrimitive.boolean)
        assertEquals(false, encoded.getValue("adPersonalization").jsonPrimitive.boolean)
    }

    @Test
    fun `unknown gdpr leaves gdprApplies out`() {
        val encoded = encode(installRequest(Consent.GRANTED_ALL.toAttributionConsent()))
            .getValue("consent").jsonObject

        assertFalse("gdprApplies" in encoded)
        assertEquals(true, encoded.getValue("adUserData").jsonPrimitive.boolean)
    }

    @Test
    fun `consent request matches the endpoint contract`() {
        val body = ConsentRequestBody(
            appsflyerId = "af-uid",
            consent = Consent.GRANTED_ALL.copy(isUserSubjectToGDPR = false).toAttributionConsent(),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(body)).jsonObject

        assertEquals("af-uid", encoded.getValue("appsflyerId").jsonPrimitive.content)
        val consent = encoded.getValue("consent").jsonObject
        assertEquals(false, consent.getValue("gdprApplies").jsonPrimitive.boolean)
        assertEquals(true, consent.getValue("adPersonalization").jsonPrimitive.boolean)
    }

    @Test
    fun `request carries appVersionCode as a number`() {
        val request = AttributionInstallRequestBody(
            sdkVersion = "1.6.1",
            osVersion = "14",
            appVersion = "1.3.6",
            appVersionCode = 172L,
            limitAdTracking = false,
            advertisingId = "ad-id",
            appsflyerId = "af-uid",
            storeCountry = "US",
            environment = "Release",
            deviceId = "device-id",
            externalAuthorization = false,
            installParams = null,
            firebaseAppInstanceId = null,
        )

        val encoded = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals(172L, encoded.getValue("appVersionCode").jsonPrimitive.long)
        assertEquals("Release", encoded.getValue("environment").jsonPrimitive.content)
    }
}
