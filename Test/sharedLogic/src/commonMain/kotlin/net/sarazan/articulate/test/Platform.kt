package net.sarazan.articulate.test

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform