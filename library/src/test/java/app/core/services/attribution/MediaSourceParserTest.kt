package app.core.services.attribution

import app.core.services.core.model.MediaSource
import app.core.services.core.model.MediaSourceType
import junit.framework.TestCase.assertEquals
import org.junit.Test

class MediaSourceParserTest {

    private val parser = DefaultMediaSourceParser

    @Test
    fun `parse returns organic MediaSource when raw is null`() {
        val result = parser.parse(null)

        assertEquals(MediaSource(), result)
    }

    @Test
    fun `parse returns organic MediaSource when raw is blank`() {
        val result = parser.parse("   ")

        assertEquals(MediaSource(), result)
    }

    @Test
    fun `parse returns organic MediaSource when raw is empty string`() {
        val result = parser.parse("")

        assertEquals(MediaSource(), result)
    }

    @Test
    fun `parse preserves original raw value in result`() {
        val raw = "Facebook Ads"

        val result = parser.parse(raw)

        assertEquals(raw, result.value)
    }

    @Test
    fun `parse detects organic media source`() {
        val result = parser.parse("organic")

        assertEquals(MediaSource("organic", MediaSourceType.ORGANIC), result)
    }

    @Test
    fun `parse detects google media source`() {
        val result = parser.parse("google")

        assertEquals(MediaSource("google", MediaSourceType.GOOGLE), result)
    }

    @Test
    fun `parse detects google media source case insensitive`() {
        val result = parser.parse("Google")

        assertEquals(MediaSourceType.GOOGLE, result.type)
    }

    @Test
    fun `parse detects facebook media source by facebook keyword`() {
        val result = parser.parse("facebook")

        assertEquals(MediaSourceType.FACEBOOK, result.type)
    }

    @Test
    fun `parse detects facebook media source by fb keyword`() {
        val result = parser.parse("fb")

        assertEquals(MediaSourceType.FACEBOOK, result.type)
    }

    @Test
    fun `parse detects facebook media source by instagram keyword`() {
        val result = parser.parse("instagram")

        assertEquals(MediaSourceType.FACEBOOK, result.type)
    }

    @Test
    fun `parse detects facebook media source by ig keyword`() {
        val result = parser.parse("ig")

        assertEquals(MediaSourceType.FACEBOOK, result.type)
    }

    @Test
    fun `parse detects tiktok media source by tiktok keyword`() {
        val result = parser.parse("tiktok")

        assertEquals(MediaSourceType.TIKTOK, result.type)
    }

    @Test
    fun `parse detects tiktok media source by tiktokglobal_int keyword`() {
        val result = parser.parse("tiktokglobal_int")

        assertEquals(MediaSourceType.TIKTOK, result.type)
    }

    @Test
    fun `parse detects tiktok media source by bytedanceglobal_int keyword`() {
        val result = parser.parse("bytedanceglobal_int")

        assertEquals(MediaSourceType.TIKTOK, result.type)
    }

    @Test
    fun `parse detects tiktok media source case insensitive`() {
        val result = parser.parse("TikTok")

        assertEquals(MediaSourceType.TIKTOK, result.type)
    }

    @Test
    fun `parse replaces spaces with underscores before matching`() {
        val result = parser.parse("tiktok global int")

        assertEquals(MediaSourceType.TIKTOK, result.type)
    }

    @Test
    fun `parse returns unknown media source for unrecognized string`() {
        val result = parser.parse("myspace")

        assertEquals(MediaSourceType.UNKNOWN, result.type)
    }

    @Test
    fun `parse returns unknown for random garbage string`() {
        val result = parser.parse("some_random_garbage_12345")

        assertEquals(MediaSourceType.UNKNOWN, result.type)
    }

    @Test
    fun `parse matches keyword contained in longer string`() {
        val result = parser.parse("tiktokglobal_E_C_P_CsEBszHJuuwD")

        assertEquals(MediaSourceType.TIKTOK, result.type)
    }

    @Test
    fun `parse matches facebook when raw contains fb as substring`() {
        val result = parser.parse("fb_android_ads")

        assertEquals(MediaSourceType.FACEBOOK, result.type)
    }
}