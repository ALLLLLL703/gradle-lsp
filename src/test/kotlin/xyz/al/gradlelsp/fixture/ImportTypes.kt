package xyz.al.gradlelsp.fixture

class ImportOuter {
    class Nested {
        class Deep
    }
    inner class Inner
    interface Contract
    enum class Mode { FIRST, SECOND }
    object Singleton
    private class Hidden
    protected class Protected
    internal class Internal
}

internal class ImportInternal
private class ImportPrivate

fun importFixtureFunction(): Any = object {}
val importFixtureProperty = 1
