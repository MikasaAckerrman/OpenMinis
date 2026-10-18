// Surface unit-test stdout (metrics lines like "chunk-arch: ...") in CI logs.
tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
    }
}
