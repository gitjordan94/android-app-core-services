package app.core.services.attribution

/** The attribution server answered with a non-2xx [statusCode]. */
internal class AttributionServerHttpException(
    val statusCode: Int,
) : Exception("Attribution server responded with HTTP $statusCode") {

    /** A 4xx: the request itself is rejected, so sending it again will not help. */
    val isClientError: Boolean get() = statusCode in 400..499
}
