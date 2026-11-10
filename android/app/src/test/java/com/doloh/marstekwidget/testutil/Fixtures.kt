package com.doloh.marstekwidget.testutil

object Fixtures {
    fun text(name: String): String =
        requireNotNull(Fixtures::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }
            .readText(Charsets.UTF_8)
}
