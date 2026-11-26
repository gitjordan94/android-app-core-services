package app.core.services.attribution

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import app.core.services.attribution.installreferrer.InstallReferrerDetails
import app.core.services.attribution.installreferrer.InstallReferrerProvider
import app.core.services.attribution.model.AdvertisingId
import app.core.services.attribution.model.AttributionInstallRequestBody
import app.core.services.attribution.model.AttributionInstallResponse
import app.core.services.attribution.model.ConsentRequestBody
import app.core.services.attribution.model.ExternalAuthorizationRequestBody
import app.core.services.billing.BillingStoreCountryProvider
import app.core.services.consent.Consent
import app.core.services.data.KeyValueStorage
import app.core.services.firebase.FirebaseAppInstanceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AttributionServerClientImplTest {

    private val storage = FakeKeyValueStorage()
    private val api = FakeAttributionServerApi()
    private val appInfo = FakeAppInfoProvider()

    private fun client() = AttributionServerClientImpl(
        config = AttributionServerConfig(
            token = "token",
            externalAuthorization = false,
            serverUrl = "https://example.com",
        ),
        appInfoProvider = appInfo,
        advertisingIdProvider = object : AdvertisingIdProvider {
            override suspend fun provide() = AdvertisingId(id = "ad-id", isLimitAdTrackingEnabled = false)
        },
        attributionServerApi = api,
        billingStoreCountryProvider = object : BillingStoreCountryProvider {
            override suspend fun getStoreCountry() = "US"
        },
        deviceIdProvider = object : DeviceIdProvider {
            override fun provide() = "device-id"
        },
        keyValueStorage = storage,
        installReferrerProvider = object : InstallReferrerProvider {
            override suspend fun getInstallReferrer(): InstallReferrerDetails? = null
        },
        dispatcher = Dispatchers.Unconfined,
        firebaseAppInstanceId = object : FirebaseAppInstanceId {
            override suspend fun getAppInstanceId(): String? = null
        },
        processLifecycle = NoOpLifecycle,
    )

    @Test
    fun `first install returns isAB from the response and sends appVersionCode`() = runBlocking {
        api.response = Result.success(AttributionInstallResponse(userId = "u1", userIp = null, isAB = true))

        val isAB = client().install("af-uid").await()

        assertEquals(true, isAB)
        assertEquals(172L, api.requests.single().appVersionCode)
        assertEquals("af-uid", api.requests.single().appsflyerId)
    }

    @Test
    fun `isAB is stored, so a later launch answers it without a new request`() = runBlocking {
        api.response = Result.success(AttributionInstallResponse(userId = "u1", userIp = null, isAB = true))
        client().install("af-uid").await()

        val isAB = client().install("af-uid").await()

        assertEquals(true, isAB)
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `install sent before isAB existed reads as false without a new request`() = runBlocking {
        storage.strings["attribution_user_id"] = "u1"

        val isAB = client().install("af-uid").await()

        assertEquals(false, isAB)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `failed request returns null, stores nothing and is sent again next time`() = runBlocking {
        api.response = Result.failure(IOException("offline"))
        val client = client()

        assertNull(client.install("af-uid").await())
        assertNull(storage.strings["attribution_user_id"])
        assertNull(storage.booleans["attribution_is_ab"])

        api.response = Result.success(AttributionInstallResponse(userId = "u1", userIp = null, isAB = false))
        assertEquals(false, client.install("af-uid").await())
        assertEquals(2, api.requests.size)
    }

    @Test
    fun `error while building the request returns null instead of throwing`() = runBlocking {
        appInfo.versionName = null

        val isAB = client().install("af-uid").await()

        assertNull(isAB)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `missing version code is sent as null`() = runBlocking {
        appInfo.versionCode = null

        client().install("af-uid").await()

        assertNull(api.requests.single().appVersionCode)
    }

    @Test
    fun `server without detection omits isAB, which is returned and stored as false`() = runBlocking {
        api.response = Result.success(AttributionInstallResponse(userId = "u1", userIp = null, isAB = null))

        assertEquals(false, client().install("af-uid").await())
        assertEquals(false, storage.booleans["attribution_is_ab"])
    }

    // region consent

    private val denied = Consent.DENIED_ALL.copy(isUserSubjectToGDPR = true)
    private val granted = Consent.GRANTED_ALL.copy(isUserSubjectToGDPR = true)

    @Test
    fun `install without an answer carries no consent`() = runBlocking {
        client().install("af-uid").await()

        assertNull(api.requests.single().consent)
    }

    @Test
    fun `consent set before the install goes only into the install`() = runBlocking {
        val client = client()

        client.setConsent(denied)
        assertTrue(api.consentRequests.isEmpty())

        client.install("af-uid").await()

        val sent = api.requests.single().consent!!
        assertEquals(true, sent.gdprApplies)
        assertFalse(sent.adUserData)
        assertFalse(sent.adPersonalization)
        assertTrue(api.consentRequests.isEmpty())
    }

    @Test
    fun `consent set after the install goes to the consent endpoint once`() = runBlocking {
        val client = client()
        client.install("af-uid").await()

        client.setConsent(granted)
        client.setConsent(granted)

        val request = api.consentRequests.single()
        assertEquals("af-uid", request.appsflyerId)
        assertTrue(request.consent.adUserData)
        assertEquals(true, request.consent.gdprApplies)
    }

    @Test
    fun `changed consent is sent again, including a change of gdpr only`() = runBlocking {
        val client = client()
        client.setConsent(denied)
        client.install("af-uid").await()

        client.setConsent(granted)
        client.setConsent(granted.copy(isUserSubjectToGDPR = false))

        assertEquals(listOf(true, true), api.consentRequests.map { it.consent.adUserData })
        assertEquals(listOf(true, false), api.consentRequests.map { it.consent.gdprApplies })
    }

    @Test
    fun `first answer after an update from a version without consent is sent`() = runBlocking {
        storage.strings["user_id"] = "af-uid"
        storage.strings["attribution_user_id"] = "u1"

        client().setConsent(granted)

        assertEquals("af-uid", api.consentRequests.single().appsflyerId)
    }

    @Test
    fun `failed consent is sent again by the next launch`() = runBlocking {
        val client = client()
        client.install("af-uid").await()
        api.consentResponse = Result.failure(IOException("offline"))
        client.setConsent(granted)

        api.consentResponse = Result.success(Unit)
        client() // a new launch retries failed requests

        assertEquals(2, api.consentRequests.size)
        // and the accepted value is not sent a third time
        client()
        assertEquals(2, api.consentRequests.size)
    }

    @Test
    fun `consent rejected with 4xx is not retried until it changes`() = runBlocking {
        val client = client()
        client.install("af-uid").await()
        api.consentResponse = Result.failure(AttributionServerHttpException(400))
        client.setConsent(granted)

        client()
        assertEquals(1, api.consentRequests.size)

        api.consentResponse = Result.success(Unit)
        client.setConsent(denied)
        assertEquals(2, api.consentRequests.size)
    }

    @Test
    fun `consent changed while the install is in flight is sent after it`() = runBlocking {
        val client = client()
        client.setConsent(denied)
        api.onInstall = { client.setConsent(granted) }

        client.install("af-uid").await()

        assertFalse(api.requests.single().consent!!.adUserData)
        assertTrue(api.consentRequests.single().consent.adUserData)
    }

    // endregion
}

private object NoOpLifecycle : Lifecycle() {
    override val currentState: State get() = State.CREATED
    override fun addObserver(observer: LifecycleObserver) = Unit
    override fun removeObserver(observer: LifecycleObserver) = Unit
}

private class FakeAppInfoProvider : AppInfoProvider {
    var versionName: String? = "1.6.1"
    var versionCode: Long? = 172L

    override fun getAppVersionName() = versionName
    override fun getAppVersionCode() = versionCode
}

private class FakeAttributionServerApi : AttributionServerApi {
    var response: Result<AttributionInstallResponse> =
        Result.success(AttributionInstallResponse(userId = "u1", userIp = null, isAB = null))
    val requests = mutableListOf<AttributionInstallRequestBody>()
    var onInstall: () -> Unit = {}

    var consentResponse: Result<Unit> = Result.success(Unit)
    val consentRequests = mutableListOf<ConsentRequestBody>()

    override suspend fun install(request: AttributionInstallRequestBody): Result<AttributionInstallResponse> {
        requests += request
        onInstall()
        return response
    }

    override suspend fun externalAuthorization(request: ExternalAuthorizationRequestBody) = Result.success(Unit)

    override suspend fun consent(request: ConsentRequestBody): Result<Unit> {
        consentRequests += request
        return consentResponse
    }
}

private class FakeKeyValueStorage : KeyValueStorage {
    val strings = mutableMapOf<String, String>()
    val booleans = mutableMapOf<String, Boolean>()

    override suspend fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }

    override suspend fun getString(key: String) = strings[key]

    override suspend fun putBoolean(key: String, value: Boolean?) {
        if (value == null) booleans.remove(key) else booleans[key] = value
    }

    override suspend fun getBoolean(key: String) = booleans[key]
}
