package dev.cuervolu.kignore.benchmark

import dev.cuervolu.kignore.IgnoreMatcher
import dev.cuervolu.kignore.IgnoreRuleSet
import dev.cuervolu.kignore.IgnoreScope
import java.lang.management.ManagementFactory

/*
 * Bytes allocated per operation, which unlike time does not depend on the machine load, so one warmed up run is a
 * reliable measurement. Run with: ./gradlew measureAllocations
 */

private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

private fun allocated(block: () -> Unit): Long {
    val before = threads.getThreadAllocatedBytes(Thread.currentThread().threadId())
    block()
    return threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before
}

/** Smallest of a few runs after warm up, so a stray allocation from the JIT does not count. */
private fun measure(block: () -> Unit): Long {
    repeat(8) { block() }
    return (1..5).minOf { allocated(block) }
}

private fun walk(
    directory: Dir,
    scope: IgnoreScope,
): Int {
    var visited = 0
    for ((name, child) in directory.dirs) {
        if (scope.ignores(name, isDirectory = true)) continue
        visited += walk(child, scope.enter(name))
    }
    for (file in directory.files) if (!scope.ignores(file)) visited++
    return visited
}

private fun retainedBytes(build: () -> Any): Long {
    fun used(): Long {
        repeat(4) { System.gc() }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
    val before = used()
    val kept = build()
    val after = used()
    java.lang.ref.Reference
        .reachabilityFence(kept)
    return after - before
}

fun main() {
    val runtime = Runtime.getRuntime()
    println("Environment")
    println("  java ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}")
    println(
        "  os ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
    )
    println("  processors ${runtime.availableProcessors()}, max heap ${runtime.maxMemory() / (1 shl 20)} MiB")
    println()
    println("Compiling rules, per rule")
    for (rules in listOf(20, 500, 5000)) {
        val text = Workload.create(10, rules, 3, 0).rulesText
        val bytes = measure { IgnoreRuleSet.parse(text) }
        val retained = retainedBytes { IgnoreRuleSet.parse(text) }
        println("  $rules rules: allocated ${bytes / rules} B per rule, retained about ${retained / rules} B per rule")
    }
    println()
    println("Querying a tree, per entry")
    for (preset in listOf(
        "1k-20r-d8-x0",
        "10k-20r-d8-x0",
        "2k-500r-d8-x0",
        "10k-20r-d3-x0",
        "2k-20r-d30-x0",
        "10k-20r-d8-x90",
    )) {
        val workload = Workload.preset(preset)
        val rules = IgnoreRuleSet.parse(workload.rulesText)
        val matcher = IgnoreMatcher.of(rules)
        val entries = workload.entries.size
        val viaMatcher = measure { for (e in workload.entries) matcher.ignores(e.path, e.isDirectory) }
        val viaScope = measure { walk(workload.root, IgnoreScope.root(rules)) }
        println("  $preset ($entries entries): matcher ${viaMatcher / entries} B, scope walk ${viaScope / entries} B")
    }
}
