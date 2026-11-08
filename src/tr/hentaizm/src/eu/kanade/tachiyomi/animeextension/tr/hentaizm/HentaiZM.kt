package eu.kanade.tachiyomi.animeextension.tr.hentaizm

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.hentaizm.extractors.VideaExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HentaiZM : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "HentaiZM"

    override val baseUrl = "https://www.hentaizm2.com"

    override val lang = "tr"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // JSON cevaplarini okumak icin yardimci fonksiyon
    private fun Response.jsonBody() = JSONObject(peekBody(Long.MAX_VALUE).string())

    // ============================== Popular ===============================
    // Site "en cok favorilere eklenenler" listesini JSON olarak veriyor
    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/api/top_favorites_list.php?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val json = response.jsonBody()
        val doc = Jsoup.parse(json.optString("html"), baseUrl)

        val animes = doc.select("div.video-list-item").map { el ->
            SAnime.create().apply {
                val link = el.selectFirst("div.title > a")!!
                title = link.ownText().trim()
                setUrlWithoutDomain(link.attr("abs:href"))
                thumbnail_url = el.selectFirst("div.img img")?.attr("abs:src")
            }
        }.distinctBy { it.url }

        val hasNext = json.optInt("page", 1) < json.optInt("total_pages", 1)
        return AnimesPage(animes, hasNext)
    }

    override fun popularAnimeSelector() = "div.video-list-item"

    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================
    // Ana sayfadaki "son eklenen bolumler" listesi
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/?page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val doc = response.asJsoup()

        val animes = doc.select("#episodes-container div.video-list-item").map { el ->
            SAnime.create().apply {
                val titleLink = el.selectFirst("div.title > a")!!
                title = titleLink.text()
                    .substringBefore(". Bölüm")
                    .substringBeforeLast(" ")
                    .trim()
                setUrlWithoutDomain(el.selectFirst("a.video-name")!!.attr("abs:href"))
                thumbnail_url = el.selectFirst("div.img img")?.attr("abs:src")
            }
        }.distinctBy { it.url }

        val hasNext = doc.selectFirst("div.pagination a.pagination-link:contains(Sonraki)") != null
        return AnimesPage(animes, hasNext)
    }

    override fun latestUpdatesSelector() = "#episodes-container div.video-list-item"

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String? = null

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id", headers))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/api/sidebar_anime_list.php".toHttpUrl().newBuilder()
            .addQueryParameter("view_type", "card")
            .addQueryParameter("type", "hentai")
            .addQueryParameter("q", query)
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val json = response.jsonBody()
        val doc = Jsoup.parse(json.optString("html"), baseUrl)

        val animes = doc.select("h3 > a").map { a ->
            SAnime.create().apply {
                title = a.text().trim()
                setUrlWithoutDomain(a.attr("abs:href"))
                // a -> h3 -> baslik kutusu -> sonucun ana kutusu
                thumbnail_url = a.parent()?.parent()?.parent()
                    ?.selectFirst("img")?.attr("abs:src")
            }
        }.distinctBy { it.url }

        // Arama sonuclari tek seferde geliyor, sonraki sayfa yok
        return AnimesPage(animes, false)
    }

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String? = null

    // =========================== Anime Details ============================
    private fun Document.detail(label: String) =
        selectFirst("p.anime-detail-item:contains($label)")?.ownText()?.trim()

    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.detail("Anime Adı:")
            ?.takeIf(String::isNotBlank)
            ?: document.title().substringBefore(" Türkçe Altyazılı")
        thumbnail_url = document.selectFirst("div.col-md-2 img")?.attr("abs:src")
        genre = document.select("a.bahtima-tag").eachText().joinToString()
        author = document.detail("Stüdyo:")?.takeIf(String::isNotBlank)
        description = document.selectFirst("div.anime-synopsis p")?.let { p ->
            p.select("br").after("\\n")
            p.text()
                .replace("\\n", "\n")
                .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
                .trim()
        }?.takeIf(String::isNotBlank)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "#episodes-list-anime a.episode-list-item"

    private fun buildEpisode(url: String, multiSeason: Boolean, fallbackText: String = ""): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(url)
            val match = EP_REGEX.find(url)
            val season = match?.groupValues?.get(1)
            val num = match?.groupValues?.get(2)
                ?: fallbackText.substringBeforeLast(". Bölüm", fallbackText)
                    .substringAfterLast(" ")
                    .ifBlank { "1" }

            episode_number = num.toFloatOrNull() ?: 1F
            name = if (multiSeason && season != null) "Sezon $season - $num. Bölüm" else "$num. Bölüm"
        }
    }

    override fun episodeFromElement(element: Element) =
        buildEpisode(element.attr("abs:href"), false, element.text())

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        val seasonIds = SEASON_REGEX
            .findAll(document.select("#season-options-anime a").joinToString(" ") { it.attr("onclick") })
            .map { it.groupValues[1] }
            .toList()
        val animeId = ANIME_ID_REGEX.find(document.html())?.groupValues?.get(1)
        val multiSeason = seasonIds.size > 1

        val fromHtml = document.select(episodeListSelector()).map {
            buildEpisode(it.attr("abs:href"), multiSeason, it.text())
        }

        // Tek sezonlu animelerde sayfadaki liste yeterli
        if (!multiSeason || animeId == null) return fromHtml.reversed()

        // Birden cok sezon varsa her sezonu siteden ayri ayri cek
        val fromApi = seasonIds.flatMap { seasonId ->
            runCatching { fetchSeason(seasonId, animeId) }.getOrDefault(emptyList())
        }
        return fromApi.ifEmpty { fromHtml }.reversed()
    }

    private fun fetchSeason(seasonId: String, animeId: String): List<SEpisode> {
        val json = client.newCall(
            GET("$baseUrl/api/episodes.php?action=get_episodes&season_id=$seasonId&anime_id=$animeId", headers),
        ).execute().use { it.jsonBody() }

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val ep = data.getJSONObject(i)
            val rawUrl = ep.optString("url").ifBlank { "/watch.php?id=${ep.optInt("id")}" }
            val url = if (rawUrl.startsWith("http")) rawUrl else baseUrl + rawUrl
            buildEpisode(url, true, ep.optString("episode_number"))
        }
    }

    // ============================ Video Links =============================
    private val videaExtractor by lazy { VideaExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    // Site kaynak adini ve video kodunu base64 ile sakliyor (kaynak adi ayrica tersine cevrilmis)
    private fun decode(value: String): String {
        val padded = value.padEnd((value.length + 3) / 4 * 4, '=')
        return String(Base64.decode(padded, Base64.URL_SAFE))
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()

        // Her butondan (kaynak adi, video kodu) cifti cikar
        val sources = doc.select("button.alt-btn").mapNotNull { btn ->
            runCatching {
                decode(btn.attr("data-provider")).reversed() to decode(btn.attr("data-videoid"))
            }.getOrNull()
        }

        // Once VIP (Ok.ru) kaynagi, cunku Videa'dan cok daha hizli
        val okru = sources.filter { it.first == "okru" }.flatMap { (_, id) ->
            runCatching {
                okruExtractor.videosFromUrl("https://ok.ru/videoembed/$id")
            }.getOrDefault(emptyList())
        }
        if (okru.isNotEmpty()) return okru

        // Ok.ru bos donerse yedek olarak Videa (Sidonia)
        return sources.filter { it.first == "videa" }.flatMap { (_, id) -> videaVideos(id) }
    }

    private fun videaVideos(id: String): List<Video> {
        val direct = runCatching {
            videaExtractor.videosFromUrl("https://videa.hu/player?v=$id")
        }.getOrDefault(emptyList())
        if (direct.isNotEmpty()) return direct

        // Olmazsa sitenin kendi oynatici sayfasindaki iframe'i dene
        val iframeSrc = runCatching {
            client.newCall(GET("https://rcdn.asyazin.com/?id=$id&source=videa", headers))
                .execute()
                .asJsoup()
                .selectFirst("iframe")?.attr("abs:src")
        }.getOrNull()

        return if (!iframeSrc.isNullOrBlank()) {
            runCatching { videaExtractor.videosFromUrl(iframeSrc) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    private val qualityRegex by lazy { Regex("""(\d+)p""") }
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),

        ).reversed()
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val EP_REGEX = Regex("""sezon-(\d+)-bolum-(\d+)""")
        private val SEASON_REGEX = Regex("""selectSeasonAnime\((\d+)""")
        private val ANIME_ID_REGEX = Regex("""anime_id=(\d+)""")

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
