package app.core.services.attribution

import app.core.services.BuildConfig
import app.core.services.attribution.model.AttributionInstallRequestBody
import app.core.services.attribution.model.AttributionInstallResponse
import app.core.services.attribution.model.ConsentRequestBody
import app.core.services.attribution.model.ExternalAuthorizationRequestBody
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.logging.HttpLoggingInterceptor

internal interface AttributionServerApi {
    suspend fun install(request: AttributionInstallRequestBody): Result<AttributionInstallResponse>

    suspend fun externalAuthorization(request: ExternalAuthorizationRequestBody): Result<Unit>

    /**
     * `POST /attribute/consent`. A non-2xx answer fails with [AttributionServerHttpException];
     * 5xx answers are retried by the client first.
     */
    suspend fun consent(request: ConsentRequestBody): Result<Unit>

    companion object {
        fun create(token: String, serverUrl: String): AttributionServerApi {
            return AttributionServerApiImpl(token, serverUrl)
        }
    }
}

private const val TIMEOUT_MILLIS = 30000L

internal class AttributionServerApiImpl(
    private val token: String,
    private val serverUrl: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AttributionServerApi {
    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = false
                }
            )
        }

        install(DefaultRequest) {
            url(serverUrl)
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header("Authorization", token)
        }

        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            exponentialDelay()
            modifyRequest { request ->
                request.headers.append("X-Retry-Count", retryCount.toString())
            }
        }

        install(HttpTimeout) {
            requestTimeoutMillis = TIMEOUT_MILLIS
            connectTimeoutMillis = TIMEOUT_MILLIS
            socketTimeoutMillis = TIMEOUT_MILLIS
        }

        engine {
            config {
                followRedirects(true)
            }

            if (BuildConfig.DEBUG) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        setLevel(HttpLoggingInterceptor.Level.BODY)
                    }
                )
            }
        }
    }

    override suspend fun install(request: AttributionInstallRequestBody): Result<AttributionInstallResponse> {
        return runCatching {
            withContext(ioDispatcher) {
                httpClient.post("/attribute/install-application-android") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body()
            }
        }
    }

    override suspend fun externalAuthorization(request: ExternalAuthorizationRequestBody): Result<Unit> {
        return runCatching {
            withContext(ioDispatcher) {
                httpClient.post("/external-authorization") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

                Unit
            }
        }
    }

    override suspend fun consent(request: ConsentRequestBody): Result<Unit> {
        return runCatching {
            withContext(ioDispatcher) {
                val response = httpClient.post("/attribute/consent") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }

                if (!response.status.isSuccess()) {
                    throw AttributionServerHttpException(response.status.value)
                }
            }
        }
    }
}