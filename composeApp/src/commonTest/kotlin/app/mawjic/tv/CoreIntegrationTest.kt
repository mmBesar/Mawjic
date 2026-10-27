package app.mawjic.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.math.max

class CoreIntegrationTest {
    @Test
    fun nativePlaylistParserPreservesMetadataAndMultipleEntries() {
        val input = """#EXTM3U
#EXTINF:-1 tvg-id="news" tvg-logo="https://example.test/logo.png" group-title="World",News HD
https://example.test/live.m3u8
#EXTINF:-1 tvg-id="kids",Kids
https://example.test/kids.m3u8
"""
        val result = PlatformBridge.parseChannelsJson(Platform.parseM3u(input))
        assertEquals(2, result.size)
        assertEquals("News HD", result[0].name)
        assertEquals("World", result[0].group)
        assertEquals("https://example.test/logo.png", result[0].logo)
    }

    @Test
    fun nativeXmltvParserNormalizesExplicitOffsetsAndArtwork() {
        val input = """<?xml version="1.0"?><!DOCTYPE tv SYSTEM "https://example.invalid/xmltv.dtd"><tv>
<channel id="news"><display-name>News &amp; More</display-name><icon src="https://example.test/channel.png"/></channel>
<programme start="20240101010000 +0200" stop="20240101020000 +0200" channel="news"><title>Morning show</title><desc>Local &amp; world</desc><icon src="https://example.test/programme.jpg"/></programme>
</tv>"""
        val guide = PlatformBridge.parseGuideJson(Platform.parseXmltv(input))
        assertEquals("https://example.test/channel.png", guide.channelLogos["news"])
        assertEquals(1, guide.programmes.size)
        assertEquals(1704063600L, guide.programmes.single().start)
        assertEquals("https://example.test/programme.jpg", guide.programmes.single().image)
    }

    @Test
    fun artworkResolverRejectsUnsafeSchemesAndHonoursMappingPrecedence() {
        assertEquals("", ArtworkResolver.safeUrl("javascript:alert(1)"))
        assertEquals("", ArtworkResolver.safeUrl("file:///etc/passwd"))
        assertEquals("https://mapping.test/a.png", ArtworkResolver.resolve(
            Channel("id", "A", "https://stream.test/live", logo = "https://playlist.test/a.png"),
            mapOf("id" to "https://xmltv.test/a.png"), mapOf("id" to "https://mapping.test/a.png"),
        ))
        assertEquals("https://xmltv.test/a.png", ArtworkResolver.resolve(
            Channel("playlist-id", "News & World", "https://stream.test/live"),
            mapOf("xmltv-id" to "https://xmltv.test/a.png"), xmltvNames = mapOf("xmltv-id" to "News World"),
        ))
        assertEquals("", ArtworkResolver.safeUrl("https:missing-authority"))
    }

    @Test
    fun allRequestedThemeOptionsExistAndSettingsRoundTrip() {
        assertEquals(4, flavours.size)
        assertEquals(14, accents.size)
        val original = AppSettings(
            flavour = "Frappé", accent = "Teal", appearance = "AMOLED Black", accentSource = "Catppuccin",
            playlistSource = "https://source.test/playlist.m3u", guideSource = "https://source.test/guide.xml",
            favourites = setOf("https://source.test/channel.m3u8"), language = "Arabic", allowInsecureTls = true,
        )
        original.save()
        val loaded = AppSettings.load()
        assertEquals(original.flavour, loaded.flavour)
        assertEquals(original.accent, loaded.accent)
        assertEquals(original.appearance, loaded.appearance)
        assertEquals(original.accentSource, loaded.accentSource)
        assertEquals(original.language, loaded.language)
        assertEquals(original.allowInsecureTls, loaded.allowInsecureTls)
        assertEquals(original.playlistSource, loaded.playlistSource)
        assertEquals(original.guideSource, loaded.guideSource)
        assertEquals(original.favourites, loaded.favourites)
    }


    @Test
    fun everyFlavourAccentCombinationHasAValidPaletteAndAccessibleForeground() {
        assertEquals(4, flavours.size)
        assertEquals(14, accents.size)
        assertEquals(listOf("English", "Arabic"), supportedLanguages)
        for (flavour in flavours) for (accent in accents) {
            val hex = catppuccinAccentHex(flavour, accent)
            assertTrue(Regex("^#[0-9A-Fa-f]{6}$").matches(hex), "$flavour / $accent: $hex")
            val foreground = primaryForegroundHex(hex)
            val light = relativeLuminance(hex)
            val foregroundLuminance = if (foreground == "#000000") 0.0 else 1.0
            val contrast = (max(light, foregroundLuminance) + 0.05) / (minOf(light, foregroundLuminance) + 0.05)
            assertTrue(contrast >= 4.5, "$flavour / $accent contrast was $contrast")
        }
        assertEquals(4, appearanceModes.size)
        assertTrue("Linux Native System Colours" in accentSources)
    }

    @Test
    fun argbColourFactoriesProduceFiniteSrgbComponents() {
        val pink = colorFromHex("#F5C2E7")
        assertTrue(pink.red.isFinite() && pink.green.isFinite() && pink.blue.isFinite() && pink.alpha.isFinite())
        assertEquals(245f / 255f, pink.red, 0.005f)
        assertEquals(194f / 255f, pink.green, 0.005f)
        assertEquals(231f / 255f, pink.blue, 0.005f)
        val black = colorFromArgb(0xFF000000L)
        assertEquals(0f, black.red)
        assertEquals(0f, black.green)
        assertEquals(0f, black.blue)
        assertEquals(1f, black.alpha)
    }

    @Test
    fun epgIndexMatchesByIdThenNormalizedChannelNameAndFindsCurrentNext() {
        val guide = GuideData(
            channelLogos = mapOf("news.xmltv" to "https://guide.test/logo.png"),
            channelNames = mapOf("news.xmltv" to "News & World"),
            programmes = listOf(
                Programme("news.xmltv", "Breakfast", "", "https://guide.test/breakfast.jpg", 100, 200),
                Programme("news.xmltv", "Midday", "", "", 200, 300),
                Programme("news.xmltv", "Evening", "", "", 300, 400),
            ),
        )
        val index = EpgIndex(guide)
        val channel = Channel("playlist-id", "News & World", "https://stream.test/news")
        assertEquals("Breakfast", index.current(channel, 150)?.title)
        assertEquals("Midday", index.next(channel, 150)?.title)
        assertEquals("Midday", index.current(channel, 250)?.title)
        assertEquals("Evening", index.next(channel, 250)?.title)
        assertEquals("Evening", index.current(channel, 350)?.title)
        assertEquals(null, index.current(channel, 450))
    }

    @Test
    fun invalidXmltvEntitiesAndTimestampsAreReported() {
        assertFailsWith<IllegalStateException> {
            PlatformBridge.parseGuideJson(Platform.parseXmltv("<!DOCTYPE tv [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><tv/>"))
        }
        assertFailsWith<IllegalStateException> {
            PlatformBridge.parseGuideJson(Platform.parseXmltv("<tv><programme start='not-a-date' stop='20240101010000' channel='x'><title>bad</title></programme></tv>"))
        }
    }
}
