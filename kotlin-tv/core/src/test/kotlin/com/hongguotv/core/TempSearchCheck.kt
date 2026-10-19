package com.hongguotv.core
import org.junit.Test
class TempSearchCheck {
    @Test fun check() {
        val repo=ContentRepository()
        for (kw in listOf("大佬，你该出手了第五季","大佬，你该出手了","大佬你该出手了")) {
            val r=runCatching { repo.search(kw,1,ContentType.SHORT) }
            r.getOrNull()?.let { println("KW=${kw} items=${it.items.size} titles=${it.items.take(4).map{s->s.title.take(16)}}") }
                .let { if (r.isFailure) println("KW=${kw} FAILED: ${r.exceptionOrNull()}") }
        }
        repo.http.connectionPool.evictAll(); repo.http.dispatcher.executorService.shutdown()
    }
}
