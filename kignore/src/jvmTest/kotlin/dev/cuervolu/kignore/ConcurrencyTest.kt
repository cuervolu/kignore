package dev.cuervolu.kignore

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Rule sets, matchers and scopes are shared between threads without synchronization. */
class ConcurrencyTest {
    private val rules =
        IgnoreRuleSet.parse("*.log\n!keep.log\nbuild/\n/docs/*.tmp\n**/cache/**\n[a-c]x?\n")
    private val nested = IgnoreRuleSet.parse("!*.log\nlocal\n")
    private val paths =
        (0 until 400).map { i ->
            "p${i % 7}/${listOf("a.log", "keep.log", "build", "docs/x.tmp", "cache/y", "bxz", "local")[i % 7]}"
        } +
            listOf("sub/a.log", "sub/deep/local", "sub/keep.log", "build/out.o", "cache/y/z")

    private fun sequential(): List<IgnoreDecision> {
        val matcher = IgnoreMatcher.of(mapOf("" to rules, "sub" to nested))
        return paths.map { matcher.decide(it) }
    }

    @Test
    fun manyThreadsGetTheSameAnswersAsOne() {
        val expected = sequential()
        val matcher = IgnoreMatcher.of(mapOf("" to rules, "sub" to nested))
        val root = IgnoreScope.root(rules)
        val failure = AtomicReference<String?>(null)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { worker ->
            pool.execute {
                start.await()
                repeat(200) {
                    paths.forEachIndexed { i, path ->
                        if (matcher.decide(path) != expected[i]) failure.compareAndSet(null, "matcher $path")
                    }
                    val sub = root.enter("sub", nested)
                    if (sub.decide("a.log") != IgnoreDecision.INCLUDED) failure.compareAndSet(null, "scope sub/a.log")
                    if (root.enter("build").isIgnored.not()) failure.compareAndSet(null, "scope build")
                    if (IgnoreRuleSet.parse("*.log\n").patterns.size != 1) failure.compareAndSet(null, "parse $worker")
                }
            }
        }
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(60, TimeUnit.SECONDS)
        assertNull(failure.get())
        assertEquals(expected, sequential())
    }
}
