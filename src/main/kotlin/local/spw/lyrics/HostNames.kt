package local.spw.lyrics

import local.spw.host.HostResolver
import com.xuncorp.spw.workshop.api.WorkshopApi

/** Resolve semantic identities in this host; do not guess an older build's names. */
object HostNames {
    val resolver = HostResolver(WorkshopApi::class.java)
    fun type(semantic: String): Class<*> = resolver.type(semantic)
    fun name(semantic: String) = type(semantic).name
}
