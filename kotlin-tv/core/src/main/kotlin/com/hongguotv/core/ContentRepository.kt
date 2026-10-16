// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)
data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val fallbackSigner = FallbackSigner()
    private val site = "https://hongguoduanju.com"
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()

    /**
     * Issue a signed request and return its body, or null if the request failed at the transport
     * level. Signing happens once per attempt: the signature covers the ticket and query, so the URL
     * and headers must come from the same [Signer.Request].
     */
    private fun signedBody(sign: (String,Map<String,String>,ByteArray?)->Signer.Request, base: String, values: Map<String,String>, body: ByteArray?): String? {
        val request=sign(base,values,body)
        return try { text(request.url,request) } catch(e: Exception) { null }
    }
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    fun comicRanking(page: Int = 1): CatalogPage {
        require(page in 1..100)
        return ComicRank.parse(text("$site/rank/hot-comic-drama?page=$page"),page)
    }
    fun home(page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(page in 1..100)
        if(type==ContentType.COMIC) return comicRanking(page)
        val data=router("$site/category?tab=1&sort_type=1"+if(page>1) "&page=$page" else "")
        val section=data.optJSONObject("category_page") ?: data.optJSONObject("category_$") ?: throw IOException("首页数据结构已变化")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("首页数据暂不可用")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",100) ?: 100
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,100))
    }
    fun search(keyword: String, page: Int = 1, type: ContentType = ContentType.SHORT): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100)
        try { val result=appSearch(keyword,page,type); if(result.items.isNotEmpty() || !result.hasMore) return result } catch (e: Exception) { if(e is SearchSessionExpiredException) throw e }
        return websiteSearch(keyword,page)
    }
    private fun websiteSearch(keyword: String, page: Int): CatalogPage {
        val data=router("$site/search/${Signer.encode(keyword)}?page=$page")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items=valid(rows.objects().map(::card))
        return CatalogPage(items,items.isNotEmpty() && page<100)
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=VendorConstants.device.toMutableMap()
        for(k in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[k]="72232"
        values["version_name"]="7.2.2.32"
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        if(type==ContentType.COMIC && page>1 && cursor==null) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val endpoint="https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v"
        // Search deliberately does NOT escalate to the fallback signer: it was measured to fail under
        // both schemes (the route itself is not serving data), so escalating would only add a doomed
        // request to every search. The website search below is the real fallback for this path.
        val signed=signer.sign(endpoint,values,null)
        val raw=text(signed.url,signed)
        if(raw.isBlank()) throw IOException("${type.label}搜索暂不可用")
        val data=runCatching { JSONObject(raw) }.getOrElse { throw IOException("${type.label}搜索暂不可用") }
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val o=router("$site/detail?series_id=$id").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        return Detail(card(o).copy(id=id),episodes)
    }
    private data class PlayVariant(val quality: Int, val codec: String, val url: String, val spade: String, val seed: String)
    // The player model has shipped in two shapes: a modern inline video_list (plain main_url plus
    // encrypt_info.spade_a) and a legacy fallback_api whose video_info must be fetched separately and
    // whose main_url is key_seed encrypted. Accept both so a server-side switch never blanks playback.
    private fun variants(items: List<JSONObject>, seed: String): List<PlayVariant> = items.mapNotNull { item ->
        val meta=item.optJSONObject("video_meta") ?: JSONObject()
        val definition=meta.str("definition").ifEmpty { meta.str("quality") }.ifEmpty { item.str("definition") }.ifEmpty { item.str("quality_type") }.ifEmpty { meta.optInt("vheight",0).toString() }
        PlayVariant(qualityNumber(definition),meta.str("codec_type").ifEmpty { item.str("codec_type") },item.str("main_url"),
            item.optJSONObject("encrypt_info")?.str("spade_a")?.ifEmpty { item.str("spade_a") } ?: item.str("spade_a"),seed)
    // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
    // Keep missing legacy codec labels usable, but never select a known incompatible variant.
    }.filter { it.url.isNotEmpty() && it.codec.lowercase() !in setOf("bytevc2","bvc2") }
    private fun fallbackUrl(model: JSONObject): String? {
        val raw=model.opt("fallback_api") ?: return null
        val value=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        return value.takeIf { it.startsWith("https://") }
    }
    /** Parse a player-model response body into the model, or null when it carries no usable data. */
    private fun parsePlayerModel(body: String?, id: String): JSONObject? {
        if(body.isNullOrEmpty()) return null
        val data=runCatching { JSONObject(body) }.getOrNull() ?: return null
        if(data.optInt("Code",data.optInt("code",0))!=0) return null
        val map=data.optJSONObject("data") ?: return null
        val entry=when {
            map.has("video_model") -> map
            map.optJSONObject(id)?.has("video_model")==true -> map.getJSONObject(id)
            else -> map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.firstOrNull { it.has("video_model") } ?: return null
        }
        val text=entry.str("video_model")
        if(text.isEmpty()) return null
        return runCatching { JSONObject(text) }.getOrNull()
    }
    /**
     * Fetch the player model from one route under one signature scheme, or null if the response
     * carried no usable model.
     *
     * Upstream signals a rejected request with HTTP 200 and a data-less body, so "did it parse into a
     * model" is the test -- a transport error and a refusal are both just null here.
     */
    private fun playerModelOrNull(sign: (String,Map<String,String>,ByteArray?)->Signer.Request, values: Map<String,String>, base: String, payload: String, id: String): JSONObject? =
        parsePlayerModel(signedBody(sign,base,values,payload.toByteArray()),id)

    // Public unencrypted 360p rendition served straight from the site. The signed player API has
    // recurring outages (110001 / empty body), and this path keeps playback working during them.
    // Not every episode has one, so probe it first: a dead URL here would look like a codec failure.
    private fun publicFallback(id: String): StreamInfo {
        val url="$site/video/public/$id/360p.mp4"
        val request=Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Range","bytes=0-0").build()
        val code=RequestScope.execute(http.newCall(request)) { it.code }
        if(code!=200 && code!=206) throw IOException("公开播放资源不可用（HTTP $code）")
        return StreamInfo(url,null,"360P（公开版）")
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val biz="""{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}"""
        // /novel/player/multi_video_model/v1/ and /novel/player/video_model/v1/ now both answer
        // HTTP 200 with an empty body: the upstream retired them. preload is the route that still
        // returns the player model, and it is the only one that offers 720P/1080P.
        val sources=listOf(
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/preload/v1/" to """{"biz_param":$biz,"mixed_video_id_map":{"1004":["$id"]}}""",
            "https://api5-normal-sinfonlineb.fqnovel.com/novel/player/multi_video_model/v1/" to """{"biz_param":$biz,"mixed_video_id_map":{"1004":["$id"]}}"""
        )
        // Scheme A is the default and covers every route before scheme B is considered at all, so the
        // fallback signer stays a genuine last resort rather than a per-route tax.
        val schemes=listOf(
            signer::sign to VendorConstants.device,
            fallbackSigner::sign to fallbackSigner.query()
        )
        var failure: IOException?=null
        for((sign,values) in schemes) {
            for((base,payload) in sources) {
                val rows=try {
                    val model=playerModelOrNull(sign,values,base,payload,id) ?: throw IOException("播放接口暂不可用")
                    val status=model.optInt("status",-1)
                    if(status!=10) throw IOException("播放状态异常：$status ${model.str("message")}".trim())
                    var found=model.optJSONArray("video_list")?.let { variants(it.objects(),"") } ?: emptyList()
                    if(found.isEmpty()) {
                        val fallback=fallbackUrl(model) ?: throw IOException("没有可用清晰度")
                        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
                        val list=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
                        found=variants(list.keys().asSequence().mapNotNull { list.optJSONObject(it) }.toList(),info.str("key_seed"))
                    }
                    found
                } catch(e: Exception) { failure=IOException(e.message ?: "播放接口暂不可用",e); continue }
                val ordered=rows.sortedByDescending { it.quality }
                val selected=ordered.firstOrNull { it.quality<=maxQuality } ?: ordered.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
                var url=cleanUrl(selected.url)
                if(!url.startsWith("https://") && !url.startsWith("http://") && selected.seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(selected.seed))
                if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
                val key=selected.spade.takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
                val quality=if(selected.quality>0) "${selected.quality}P"+(if(selected.quality>maxQuality) "（兼容资源）" else "") else "自动"
                return StreamInfo(url,key,quality)
            }
        }
        // Both signature schemes and every route were refused. That is the one situation that may
        // mean the device identity is flagged, so offer it for rotation -- rate-limited internally,
        // because a sustained upstream outage looks identical from here and must not churn devices.
        val rotated=DeviceFingerprint.rotateIfRejected()
        if(rotated!=null) {
            val retry=runCatching { stream(id,maxQuality) }.getOrNull()
            if(retry!=null) return retry
        }
        // Still nothing: fall back to the public rendition instead of refusing to play.
        // If that episode has no public copy either, surface the signed-API error, which is the real cause.
        return try { publicFallback(id) } catch(e: Exception) { throw failure ?: e }
    }
    companion object {
        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
