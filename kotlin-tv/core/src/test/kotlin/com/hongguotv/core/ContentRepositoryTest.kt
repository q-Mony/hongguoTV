package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ContentRepositoryTest {
    // Modern shape: the batch route returns the player model inline, with plain main_url per variant.
    private fun streamRepository(vararg variants: Triple<Int,String,String>, status: Int = 10, inline: Boolean = true) = repository { request ->
        if(inline) {
            val rows=JSONArray()
            variants.forEach { (height,codec,url) ->
                rows.put(JSONObject().put("main_url",url)
                    .put("video_meta",JSONObject().put("vheight",height).put("codec_type",codec).put("definition","${height}p")))
            }
            JSONObject().put("data",JSONObject().put("123",JSONObject().put("video_model",
                JSONObject().put("status",status).put("video_list",rows).toString()))).toString()
        } else if(request.url.host=="example.com") {
            // Legacy shape: fallback_api must be fetched, and video_list is keyed by video_N.
            val rows=JSONObject()
            variants.forEachIndexed { index,(height,codec,url) -> rows.put("video_$index",JSONObject().put("vheight",height).put("codec_type",codec).put("main_url",url)) }
            JSONObject().put("video_info",JSONObject().put("data",JSONObject().put("video_list",rows))).toString()
        } else JSONObject().put("data",JSONObject().put("123",JSONObject().put("video_model",
            JSONObject().put("status",status).put("fallback_api",JSONObject().put("fallback_api","https://example.com/stream").toString()).toString()))).toString()
    }

    @Test fun inlinePlayerModelReadsVideoListWithoutASecondRequest() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            val rows=JSONArray().put(JSONObject().put("main_url","https://example.com/avc")
                .put("video_meta",JSONObject().put("vheight",720).put("codec_type","h264").put("definition","720p")))
            JSONObject().put("data",JSONObject().put("123",JSONObject().put("video_model",
                JSONObject().put("status",10).put("video_list",rows).toString()))).toString()
        }
        val result=repo.stream("123",720)
        assertEquals("https://example.com/avc",result.url)
        assertEquals("720P",result.quality)
        assertEquals(listOf("/novel/player/multi_video_model/preload/v1/"),paths)
    }

    @Test fun retiredBatchRouteIsRetriedWhenThePreloadRouteIsUnavailable() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(request.url.encodedPath.contains("preload")) """{"code":110001,"data":{}}"""
            else {
                val rows=JSONArray().put(JSONObject().put("main_url","https://example.com/single")
                    .put("video_meta",JSONObject().put("vheight",480).put("codec_type","h264").put("definition","480p")))
                JSONObject().put("data",JSONObject().put("video_model",
                    JSONObject().put("status",10).put("video_list",rows).toString())).toString()
            }
        }
        val result=repo.stream("123",720)
        assertEquals("https://example.com/single",result.url)
        assertEquals("480P",result.quality)
        assertEquals(2,paths.size)
    }

    @Test fun legacyFallbackApiStillResolvesWhenInlineListIsAbsent() {
        val repo=streamRepository(Triple(720,"h264","https://example.com/legacy-fallback"),inline=false)
        assertEquals("https://example.com/legacy-fallback",repo.stream("123",720).url)
    }

    @Test fun nonReadyPlaybackStatusFallsBackToPublicRendition() {
        val repo=streamRepository(Triple(720,"h264","https://example.com/avc"),status=4)
        val result=repo.stream("123",720)
        assertEquals("https://hongguoduanju.com/video/public/123/360p.mp4",result.url)
        assertEquals("360P（公开版）",result.quality)
    }

    @Test fun unavailablePlayerApiFallsBackToPublicRendition() {
        val repo=repository { """{"Code":110001,"Message":"未知异常"}""" }
        val result=repo.stream("123",720)
        assertEquals("https://hongguoduanju.com/video/public/123/360p.mp4",result.url)
        assertEquals("360P（公开版）",result.quality)
        assertNull(result.key)
    }

    @Test fun missingPublicRenditionReportsFailureInsteadOfADeadUrl() {
        // Some episodes have no public copy; the probe must reject it rather than hand back a 404 URL.
        val repo=ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val missing=chain.request().url.encodedPath.startsWith("/video/public/")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if(missing) 404 else 200).message(if(missing) "Not Found" else "OK")
                .body((if(missing) "" else """{"Code":110001,"Message":"未知异常"}""").toResponseBody()).build()
        }.build())
        assertThrows(IOException::class.java) { repo.stream("123",720) }
    }

    @Test fun incompatibleByteVc2DoesNotBecomeAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(1080,"bytevc1","https://example.com/hevc"))
        val result=repo.stream("123",720)
        assertEquals("https://example.com/hevc",result.url)
        assertEquals("1080P（兼容资源）",result.quality)
    }

    @Test fun compatibleLowerQualityWinsBeforeHigherResolutionFallback() {
        val repo=streamRepository(Triple(720,"bytevc2","https://example.com/unsupported"),Triple(480,"h264","https://example.com/avc"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("480P",repo.stream("123",720).quality)
    }

    @Test fun entirelyIncompatibleSourceNeverBecomesAudioOnlyPlayback() {
        val repo=streamRepository(Triple(720,"ByteVC2","https://example.com/unsupported"),Triple(480,"bvc2","https://example.com/unsupported2"))
        // The incompatible variants must not be selected; playback drops to the public rendition instead.
        val result=repo.stream("123",720)
        assertFalse(result.url.contains("unsupported"))
        assertEquals("360P（公开版）",result.quality)
    }

    @Test fun legacyMissingCodecLabelKeepsQualityLimit() {
        val repo=streamRepository(Triple(720,"","https://example.com/legacy"),Triple(1080,"bytevc1","https://example.com/hevc"))
        assertEquals("720P",repo.stream("123",720).quality)
    }

    private fun repository(clock: () -> Long = System::currentTimeMillis, respond: (Request) -> String) = ContentRepository(
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(),clock
    )

    private fun rankPayload(page: Int = 1, totalPages: Int = 2) = JSONObject()
        .put("isSuccess",true)
        .put("rankList",JSONArray().put(JSONObject().put("seriesId","123").put("title","测试漫剧")
            .put("cover","https:\\u002F\\u002Fexample.com\\u002Fcover.jpg")
            .put("episodeCount",12).put("tags",JSONArray().put("玄幻"))))
        .put("pagination",JSONObject().put("pageNum",page).put("totalPages",totalPages))

    private fun script(name: String, args: JSONArray) =
        "<script data-fn-name=\"$name\" data-fn-args=\"${args.toString().replace("&","&amp;").replace("\"","&quot;")}\"></script>"

    private fun currentRank(payload: JSONObject) = script("mergeLoaderData",JSONArray()
        .put("rank_hot-comic-drama/page").put(JSONArray().put(JSONObject().put("key","content")
            .put("routerDataFnName","p").put("routerDataFnArgs",JSONArray().put(payload.toString())))))

    @Test fun comicRankingPreservesSourceRankHeatAndUpdateRatherThanRenumbering() {
        val payload=rankPayload(2,5)
        payload.getJSONArray("rankList").getJSONObject(0).put("rank",23).put("heatText","5746万热度")
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"9月22日已更新"}}};</script>"""+currentRank(payload)
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(23,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("9月22日已更新",result.ranking.updatedText)
        assertTrue(result.hasMore)
        assertFalse(result.items.single().toJson().has("rank"))
    }

    @Test fun missingRankIsNotInventedAndLastPageStops() {
        val result=repository { currentRank(rankPayload(5,5)) }.comicRanking(5)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(null,""),result.ranking!!.positions["123"])
        assertFalse(result.hasMore)
    }

    @Test fun renderedRankingPreservesPageTwoRankAndHeat() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2,"updatedText":"今日已更新"}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">21</span>
                <h2 id="rank-title-123">漫剧</h2><p class="pc-metrics-example"><span>5746万热度</span></p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=5">5</a></nav>"""
        }
        val result=repo.comicRanking(2)
        assertNotNull(result.ranking)
        assertEquals(RankPosition(21,"5746万热度"),result.ranking!!.positions["123"])
        assertEquals(5,result.ranking.totalPages)
        assertEquals("今日已更新",result.ranking.updatedText)
    }

    @Test fun rankingRejectsUnexpectedPageInsteadOfShowingWrongRanks() {
        val repo=repository { currentRank(rankPayload(1,5)) }
        assertThrows(IOException::class.java) { repo.comicRanking(2) }
        assertThrows(IllegalArgumentException::class.java) { repo.comicRanking(0) }
    }

    @Test fun renderedLastPageCountsCurrentPageWithoutAnAnchor() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":5}}};</script>
                <article aria-labelledby="rank-title-123"><span class="pc-badge-number-example">81</span><h2 id="rank-title-123">漫剧</h2></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=4">4</a><span aria-current="page">5</span></nav>"""
        }
        val result=repo.comicRanking(5)
        assertEquals(5,result.ranking!!.totalPages)
        assertFalse(result.hasMore)
    }

    @Test fun comicHomeUsesDedicatedRankAndRealPagination() {
        val repo=repository { request ->
            assertEquals("/rank/hot-comic-drama",request.url.encodedPath)
            assertEquals("2",request.url.queryParameter("page"))
            currentRank(rankPayload(2,2))
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试漫剧",page.items.single().title)
        assertEquals("https://example.com/cover.jpg",page.items.single().cover)
        assertEquals("全 12 集",page.items.single().badge)
        assertFalse(page.hasMore)
    }

    @Test fun legacyRankIsStillSupported() {
        val repo=repository { script("r",JSONArray().put("rank_hot-comic-drama/page").put("content").put(rankPayload())) }
        assertTrue(repo.home(1,ContentType.COMIC).hasMore)
    }

    @Test fun comicHomeDoesNotUseUnrelatedRankData() {
        val repo=repository { script("r",JSONArray().put("rank_hot-real-drama/page").put("content").put(rankPayload())) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun rejectedComicRankRemainsAnError() {
        val repo=repository { currentRank(rankPayload().put("isSuccess",false)) }
        assertThrows(IOException::class.java) { repo.home(1,ContentType.COMIC) }
    }

    @Test fun renderedComicRankWorksWhenStreamedPayloadIsMissing() {
        val repo=repository {
            """<script>_ROUTER_DATA={"loaderData":{"rank_hot-comic-drama/page":{"rankKey":"comic","pageNum":2}}};</script>
                <article aria-labelledby="rank-title-123"><a href="/detail?series_id=123"><img src="https://example.com/a.jpg"></a>
                <h2 id="rank-title-123">测试 &amp; 漫剧</h2><p class="pc-description-example">简介</p><p class="pc-metrics-example">123万热度</p></article>
                <nav aria-label="榜单分页"><a href="/rank/hot-comic-drama?page=1">1</a><span aria-current="page">2</span><a href="/rank/hot-comic-drama?page=3">3</a></nav>"""
        }
        val page=repo.home(2,ContentType.COMIC)
        assertEquals("测试 & 漫剧",page.items.single().title)
        assertEquals("https://example.com/a.jpg",page.items.single().cover)
        assertTrue(page.hasMore)
    }

    @Test fun comicSearchCarriesCursorAndKeepsTypesAndQueriesSeparate() {
        val requests=mutableListOf<Request>()
        val repo=repository { request ->
            requests+=request
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","comic-session").put("passback","comic-cursor").put("next_offset",25))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals("comic-session",requests[1].url.queryParameter("search_id"))
        assertEquals("comic-cursor",requests[1].url.queryParameter("passback"))
        assertEquals("25",requests[1].url.queryParameter("offset"))
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("重生",2,ContentType.COMIC) }
        assertEquals(2,requests.size)
    }

    @Test fun expiredCursorRequestsFreshSearchInsteadOfDuplicateOffsetPage() {
        var now=0L
        var requests=0
        val repo=repository(clock={ now }) {
            requests++
            JSONObject().put("code",0).put("search_tabs",JSONArray().put(searchTab(19,"999")
                .put("search_id","session").put("passback","cursor"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        now=300001L
        assertThrows(SearchSessionExpiredException::class.java) { repo.search("修仙",2,ContentType.COMIC) }
        assertEquals(1,requests)
        repo.search("修仙",1,ContentType.COMIC)
        repo.search("修仙",2,ContentType.COMIC)
        assertEquals(3,requests)
    }

    @Test fun shortHomeStillUsesCategoryAndStopsOnLastPage() {
        val repo=repository { request ->
            assertEquals("1",request.url.queryParameter("tab"))
            """<script>_ROUTER_DATA={"loaderData":{"category_$":{"recommendList":[{"series_id":"456","series_name":"测试短剧"}],"pagination":{"totalPages":1}}}};</script>"""
        }
        val result=repo.home(1,ContentType.SHORT)
        assertEquals("456",result.items.single().id)
        assertFalse(result.hasMore)
    }

    private fun searchTab(type: Int,id: String) = JSONObject().put("tab_type",type).put("has_more",true)
        .put("data",JSONArray().put(JSONObject().put("video_data",JSONArray().put(JSONObject()
            .put("series_id",id).put("title","测试剧集").put("episode_cnt",8)))))

    @Test fun comicSearchRequestsAndSelectsOnlyComicTab() {
        var requests=0
        val repo=repository { request ->
            requests++
            assertEquals("19",request.url.queryParameter("tab_type"))
            assertEquals(if(requests==1) "0" else "20",request.url.queryParameter("offset"))
            assertEquals("修仙",request.url.queryParameter("query"))
            JSONObject().put("code",0).put("search_tabs",JSONArray()
                .put(searchTab(11,"111")).put(searchTab(19,"999"))).toString()
        }
        repo.search("修仙",1,ContentType.COMIC)
        assertEquals(listOf("999"),repo.search("修仙",2,ContentType.COMIC).items.map { it.id })
    }

    @Test fun emptyComicSearchDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":19,"data":[],"has_more":false}]}""" }
        assertTrue(repo.search("无结果",1,ContentType.COMIC).items.isEmpty())
        assertEquals(1,requests)
    }

    @Test fun missingComicTabDoesNotFallBackToShortResults() {
        var requests=0
        val repo=repository { requests++; """{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}""" }
        assertThrows(IOException::class.java) { repo.search("修仙",1,ContentType.COMIC) }
        assertEquals(2,requests)
    }

    @Test fun comicSearchFallsBackToWebsiteWhenAppEndpointReturnsEmptyBody() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) ""
            else """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"789","series_name":"网页漫剧"}]}}}};</script>"""
        }
        assertEquals("789",repo.search("修仙",1,ContentType.COMIC).items.single().id)
        assertEquals("/reading/bookapi/search/tab/v",paths.first())
        assertTrue(paths.last().startsWith("/search/"))
    }

    @Test fun shortSearchKeepsWebsiteFallback() {
        val paths=mutableListOf<String>()
        val repo=repository { request ->
            paths+=request.url.encodedPath
            if(paths.size==1) throw IOException("offline")
            """<script>_ROUTER_DATA={"loaderData":{"search_page":{"searchList":[{"series_id":"456","series_name":"测试短剧"}]}}}};</script>"""
        }
        assertEquals("456",repo.search("短剧",1,ContentType.SHORT).items.single().id)
        assertEquals(2,paths.size)
    }

    // Scheme B is the fallback: when scheme A's response carries no model, the request is retried
    // with the minimal signer, which must still yield a playable variant.
    @Test fun fallbackSignerIsUsedWhenThePrimarySchemeYieldsNoModel() {
        val schemes=mutableListOf<String>()
        val repo=repository { request ->
            // Scheme A sends x-medusa-less full headers including x-ladon; scheme B does not.
            schemes+=(if(request.header("x-ladon")!=null) "A" else "B")
            if(request.header("x-ladon")!=null) ""
            else {
                val rows=JSONArray().put(JSONObject().put("main_url","https://example.com/fallback-scheme")
                    .put("video_meta",JSONObject().put("vheight",1080).put("codec_type","bytevc1").put("definition","1080p")))
                JSONObject().put("data",JSONObject().put("123",JSONObject().put("video_model",
                    JSONObject().put("status",10).put("video_list",rows).toString()))).toString()
            }
        }
        val result=repo.stream("123",1080)
        assertEquals("https://example.com/fallback-scheme",result.url)
        assertEquals("1080P",result.quality)
        // Scheme A must be attempted first, on every route, before scheme B is tried at all.
        assertTrue("scheme A must be tried first",schemes.first()=="A")
        assertTrue("scheme B must actually be exercised",schemes.contains("B"))
    }

    // The happy path must not pay for the fallback: one route, one scheme.
    @Test fun primarySchemeSucceedsWithoutTouchingTheFallback() {
        val schemes=mutableListOf<String>()
        val repo=repository { request ->
            schemes+=(if(request.header("x-ladon")!=null) "A" else "B")
            val rows=JSONArray().put(JSONObject().put("main_url","https://example.com/primary")
                .put("video_meta",JSONObject().put("vheight",720).put("codec_type","bytevc1").put("definition","720p")))
            JSONObject().put("data",JSONObject().put("123",JSONObject().put("video_model",
                JSONObject().put("status",10).put("video_list",rows).toString()))).toString()
        }
        assertEquals("https://example.com/primary",repo.stream("123",720).url)
        assertEquals(listOf("A"),schemes)
    }

    // A sustained outage must not churn device identities. The first total failure may rotate once
    // (we cannot yet tell a ban from an outage), but every failure after that must leave the
    // fingerprint alone until the interval elapses. That bound is the whole point of the guard.
    @Test fun repeatedTotalFailureRotatesAtMostOnce() {
        DeviceFingerprint.restore("1111111111111111111","2222222222222222222",0)
        val repo=repository { "" }
        val seen=mutableSetOf<Pair<String,String>>()
        repeat(5) {
            runCatching { repo.stream("123",1080) }
            seen+=DeviceFingerprint.current()
        }
        assertTrue("an outage must not mint a new device per failure (saw ${seen.size})",seen.size<=2)
        // Whatever it settled on must be a well-formed identity, not a degraded value.
        val (deviceId,iid)=DeviceFingerprint.current()
        assertTrue(deviceId.matches(Regex("[0-9]{19}")))
        assertTrue(iid.matches(Regex("[0-9]{19}")))
    }

    @Test fun rejectionRotatesTheDeviceIdentityAtMostOncePerInterval() {
        DeviceFingerprint.restore("1111111111111111111","2222222222222222222",0)
        val first=DeviceFingerprint.rotateIfRejected(1_000_000L)
        assertNotNull("a rejection with no recent rotation should rotate",first)
        assertNotEquals("1111111111111111111" to "2222222222222222222",DeviceFingerprint.current())
        // A second rejection inside the interval must be declined: this is what stops an outage
        // from being mistaken for a ban.
        assertNull(DeviceFingerprint.rotateIfRejected(1_000_000L+1000))
        assertEquals(first,DeviceFingerprint.current())
        // Past the interval it is allowed again.
        assertNotNull(DeviceFingerprint.rotateIfRejected(1_000_000L+DeviceFingerprint.MIN_ROTATION_INTERVAL_MILLIS+1))
    }
}
