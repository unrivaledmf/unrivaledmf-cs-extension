package com.fadil

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.util.ArrayList

class AnichinProvider : MainAPI() {
    override var mainUrl = "https://anichin.vip/"
    override var name =  "Anichin"
    override var hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    companion object {
        fun getType(t: String): TvType {
            return when {
                t.contains("TV") -> TvType.Anime
                t.contains("Movie") -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }
        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }
// what i don't have again is getProperAnimeLink, getBaseUrl, and if else of override suspend fun load 

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing/page/" to "Anime Ongoing",
        "$mainUrl/completed/page/" to "Anime Completed",
        "&status=&type=&order=update" to "New Episode",
        "&status=&type=&order=latest" to "New Anime",
        "&sub=&order=popular" to "Popular Anime",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.excstf > article > div").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst(" div > a > div.tt")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")!!.attr("href")
        val posterUrl = this.select("div.limit > img").attr("src").toString()
        val epNum = this.selectFirst(" div.eggepisode")?.ownText()?.replace(Regex("[^0-9]"), "")?.trim()
            ?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }
    

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s$query&post_type=anime"
        val document = app.get(
            link
        ).document
        return document.select("div.listupd > article > div").map {
            val title = it.selectFirst("a > div.tt")!!.ownText().trim()
            val href = it.selectFirst("div > a")!!.attr("href")
            val posterUrl = it.selectFirst("img")!!.attr("src").toString()
            newAnimeSearchResponse(title, href, TvType.Anime){
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url
        ).document
        val title = document.selectFirst("div.infox > h1")?.ownText()
            ?.replace(":",  "")?.trim().toString()
        val poster = document.selectFirst("div.bigcontent.nobigcv img")?.attr("src")
        val tags = document.select("div.infox > div > div.info-content > div.genxed > a").map { it.text()}
        val type = document.selectFirst("div.infox > div > div.info-content > div.spe > span:nth-child(8)")?.ownText()
            ?.replace( ":", "")?.trim() ?: "tv"

        val year = Regex("\\d, ([0-9]*)").find(
            document.select("div.info-content > div.spe > span:nth-child(12) > time").text()
        )?.groupValues?.get(1)?.toIntOrNull()
        val status = getStatus(
            document.selectFirst("div.infox > div > div.info-content > div.spe > span:nth-child(1)")!!.ownText()
                .replace(":", "")
                .trim()
        )
        val description = document.select("div.bixbox.synp > div.entry-content > p").text()

        val (malId, anilistId, image, cover) = getTracker(title, type, year)

        val episodes = document.select("div.eplister")[1].select("ul > li").mapNotNull {
            val name = it.selectFirst("a")?.text() ?: return@mapNotNull null
            val episode = Regex( "Episode\\s?([0-9]+)").find(name)?.groupValues?.getOrNull(0)
                ?: it.selectFirst("a")?.text()
            val link = fixUrl(it.selectFirst("a")!!.attr("href"))
            Episode(link, name, episode = episode?.toIntOrNull())
        }.reversed()

        val recommendations =
            document.select("div > div.listupd > article > div > a").map {
                val  recName = it.selectFirst("div > a > div.tt")!!.text()
                val  recHref = it.selectFirst("a")!!.attr("href")
                val recPosterUrl = it.selectFirst(" img ")?.attr("src").toString()
                newAnimeSearchResponse(recName, recHref, TvType.Anime) {
                    this.posterUrl = recPosterUrl
                }
            }

        return newAnimeLoadResponse(title, url, getType(type)) {
            engName = title
            posterUrl = image ?: poster
            backgroundPosterUrl = cover ?: image ?: poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            addMalId(malId)
            addAniListId(anilistId?.toIntOrNull())
            this.recommendations = recommendations
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val sources = document.select("div.video-content > div.lowvid  > div.player-embed").mapNotNull { 
            fixUrl(it.select("iframe").attr("src"))
         }
         sources.apmap {
            loadExtractor(it, data, subtitleCallback,callback)
         }

         return true
    }

//=============================================================


    private suspend fun getTracker(title: String?, type: String?, year: Int?): Tracker {
        val res = app.get("https://api.consumet.org/meta/anilist/$title")
            .parsedSafe<AniSearch>()?.results?.find { media ->
                (media.title?.english.equals(title, true) || media.title?.romaji.equals(
                    title,
                    true
                )) || (media.type.equals(type, true) && media.releaseDate == year)
            }
        return Tracker(res?.malId, res?.aniId, res?.image, res?.cover)
    }

    data class Tracker(
        val malId: Int? = null,
        val aniId: String? = null,
        val image: String? = null,
        val cover: String? = null,
    )

    data class Title(
        @JsonProperty("romaji") val romaji: String? = null,
        @JsonProperty("english") val english: String? = null,
    )

    data class Results(
        @JsonProperty("id") val aniId: String? = null,
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("releaseDate") val releaseDate: Int? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("cover") val cover: String? = null,
    )

    data class AniSearch(
        @JsonProperty("results") val results: ArrayList<Results>? = arrayListOf(),
    )

}


