package net.sarazan.articulate.showcase

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform