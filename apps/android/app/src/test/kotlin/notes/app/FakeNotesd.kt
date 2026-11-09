package notes.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import notes.app.notes.names
import notes.core.api.Invitee
import notes.core.api.Member
import notes.core.api.PublishSettings
import notes.core.api.RemoteInvite
import notes.core.api.RemoteNote
import notes.core.api.RemoteSpace
import notes.core.api.Session
import notes.core.api.SharedSpace
import notes.core.api.SignIn
import notes.core.api.SpaceTrait
import notes.core.api.SyncPage
import notes.core.db.Spaces

/**
 * Enough of notesd for shared spaces (§7.9), from [me]'s side: the space list with its `shared` trait worked out
 * as the server does, invitations, people, membership calls and each feed's notes. Every request is in [requests].
 */
class FakeNotesd(val me: Member = Member(1, "me")) : AutoCloseable {
    val server = MockWebServer()

    /** Every request, as `METHOD /path`: a test reads it while the server's thread adds to it. */
    val requests = CopyOnWriteArrayList<String>()

    /** By id; the default space is always there. */
    val spaces = LinkedHashMap<String, RemoteSpace>().apply {
        put(Spaces.DEFAULT, RemoteSpace(Spaces.DEFAULT, "Notes"))
    }
    val invites = ArrayList<RemoteInvite>()
    var people = listOf(me, Member(2, "ana"), Member(3, "ben"), Member(4, "cy"))

    /** Each feed's notes: `""` is the account's, else a shared space's id. */
    val feeds = HashMap<String, List<RemoteNote>>()

    /** Spaces an invitation joins: what accepting it makes the space. */
    val invited = HashMap<String, RemoteSpace>()

    /** Published spaces' addresses, by space id; kept when the site is taken down, as the server keeps them. */
    val published = LinkedHashMap<String, PublishSettings>()

    /** Spaces the server holds locked notes in: publishing them is refused. */
    val lockedIn = HashSet<String>()

    /** Requests, as [requests] names them, held unanswered until [release]: what a slow server looks like. */
    val held = HashSet<String>()
    private val gate = CountDownLatch(1)

    fun release() = gate.countDown()

    /** Requests, as [requests] names them, answered with a 500. */
    val failing = HashSet<String>()

    private val json = Json { encodeDefaults = false }

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val name = "${request.method} ${request.url.encodedPath}"
                // Seen as soon as it arrives, held or not.
                requests += name
                // Outside the lock: every other request goes on meanwhile.
                if (name in held) gate.await(10, TimeUnit.SECONDS)
                return synchronized(this@FakeNotesd) {
                    if (name in failing) error(500, "internal") else answer(request)
                }
            }
        }
        server.start()
    }

    val url: String get() = server.url("/").toString()

    /** Signs [graph] in here as [me], its id given unless [withId] is false (a server from before shared spaces). */
    fun signIn(graph: AppGraph, withId: Boolean = true) = runBlocking {
        graph.accounts.signedIn(
            url,
            SignIn(
                Session("s", "device", "P", 1, 1),
                me.username,
                "a",
                Long.MAX_VALUE,
                "r",
                Long.MAX_VALUE,
                userId = if (withId) me.id else 0
            )
        )
    }

    /** A space someone shares with [me]: [owner] first, then the others. */
    fun memberOf(id: String, name: String, owner: Member, vararg others: Member, icon: String = "") =
        synchronized(this) {
            spaces[id] = shared(RemoteSpace(id, name, icon), SharedSpace("member", listOf(owner, me) + others))
        }

    /** A space of [me]'s, shared with [members] and with [invitees] invited. */
    fun owned(id: String, name: String, members: List<Member>, invitees: List<Member> = emptyList()) =
        synchronized(this) {
            val pending = invitees.map { Invitee("inv-${it.id}", it.id, it.username, it.displayName, it.avatar) }
            spaces[id] = shared(RemoteSpace(id, name), SharedSpace("owner", listOf(me) + members, pending))
        }

    /** An invitation waiting for [me]: accepting it makes [me] a member of [id]. */
    fun invite(inviteId: String, id: String, name: String, from: Member, icon: String = "") = synchronized(this) {
        invites += RemoteInvite(inviteId, id, name, icon, from, 1)
        invited[inviteId] = shared(RemoteSpace(id, name, icon), SharedSpace("member", listOf(from, me)))
    }

    private fun answer(r: RecordedRequest): MockResponse {
        val path = r.url.encodedPath.removePrefix("/api/v1")
        val parts = path.trim('/').split('/')
        return when {
            r.method == "GET" && path == "/spaces" ->
                ok(
                    """{"spaces":${json.encodeToString(
                        ListSerializer(RemoteSpace.serializer()),
                        spaces.values.toList()
                    )}}"""
                )

            r.method == "PUT" && parts.size == 2 && parts[0] == "spaces" -> {
                val body = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
                val name = body["name"]!!.jsonPrimitive.content
                val icon = body["icon"]?.jsonPrimitive?.content
                val space = spaces[parts[1]]?.let { it.copy(name = name, icon = icon ?: it.icon) }
                    ?: RemoteSpace(parts[1], name, icon.orEmpty())
                spaces[space.id] = space
                ok(json.encodeToString(RemoteSpace.serializer(), space))
            }

            r.method == "GET" && path == "/invites" ->
                ok("""{"invites":${json.encodeToString(ListSerializer(RemoteInvite.serializer()), invites)}}""")

            r.method == "GET" && path == "/people" ->
                ok("""{"people":${json.encodeToString(ListSerializer(Member.serializer()), people)}}""")

            r.method == "GET" && path == "/locks" -> ok("""{"keys":[]}""")

            r.method == "GET" && path == "/sync" -> page("")

            r.method == "GET" && parts.size == 3 && parts[0] == "spaces" && parts[2] == "sync" -> page(parts[1])

            r.method == "POST" && parts.size == 3 && parts[0] == "invites" && parts[2] == "accept" -> {
                val space = invited.remove(parts[1]) ?: return error(404, "not_found")
                invites.removeAll { it.id == parts[1] }
                spaces[space.id] = space
                ok(json.encodeToString(RemoteSpace.serializer(), space))
            }

            r.method == "POST" && parts.size == 3 && parts[0] == "invites" && parts[2] == "decline" -> {
                invites.removeAll { it.id == parts[1] }
                MockResponse.Builder().code(204).build()
            }

            r.method == "POST" && parts.size == 3 && parts[0] == "spaces" && parts[2] == "invites" -> {
                val space = spaces[parts[1]] ?: return error(404, "not_found")
                val userId = Json.parseToJsonElement(r.body!!.utf8()).jsonObject["userId"]!!.jsonPrimitive.long
                val who = people.first { it.id == userId }
                val invitee = Invitee("inv-$userId", userId, who.username)
                val before = space.shared ?: SharedSpace("owner", listOf(me))
                spaces[space.id] = shared(space, before.copy(invites = before.invites + invitee))
                MockResponse.Builder().code(201).addHeader("Content-Type", "application/json")
                    .body(json.encodeToString(Invitee.serializer(), invitee)).build()
            }

            r.method == "DELETE" && parts.size == 4 && parts[0] == "spaces" && parts[2] == "invites" -> {
                val space = spaces.getValue(parts[1])
                val before = space.shared!!
                spaces[space.id] = shared(space, before.copy(invites = before.invites.filter { it.id != parts[3] }))
                MockResponse.Builder().code(204).build()
            }

            r.method == "DELETE" && parts.size == 4 && parts[0] == "spaces" && parts[2] == "members" -> {
                val userId = parts[3].toLong()
                if (userId == me.id) {
                    spaces.remove(parts[1])
                } else {
                    val space = spaces.getValue(parts[1])
                    val before = space.shared!!
                    spaces[space.id] = shared(space, before.copy(members = before.members.filter { it.id != userId }))
                }
                MockResponse.Builder().code(204).build()
            }

            r.method == "PUT" && parts.size == 3 && parts[0] == "spaces" && parts[2] == "owner" -> {
                val space = spaces.getValue(parts[1])
                val userId = Json.parseToJsonElement(r.body!!.utf8()).jsonObject["userId"]!!.jsonPrimitive.long
                val members = space.shared!!.members
                val owner = members.first { it.id == userId }
                spaces[space.id] = shared(space, SharedSpace("member", listOf(owner) + members.filter { it != owner }))
                MockResponse.Builder().code(204).build()
            }

            r.method == "GET" && parts.size == 3 && parts[0] == "spaces" && parts[2] == "publish" ->
                published[parts[1]]?.let { ok(json.encodeToString(PublishSettings.serializer(), it)) }
                    ?: error(404, "not_published")

            r.method == "PUT" && parts.size == 3 && parts[0] == "spaces" && parts[2] == "publish" -> {
                val space = spaces[parts[1]] ?: return error(404, "not_found")
                val body = Json.parseToJsonElement(r.body!!.utf8()).jsonObject
                val before = published[space.id]
                val handle = body["handle"]?.jsonPrimitive?.content
                    ?: before?.handle
                    ?: space.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                val origin = body["origin"]?.jsonPrimitive?.content ?: before?.origin
                when {
                    space.id == Spaces.DEFAULT -> return error(409, "space_default")

                    space.id in lockedIn -> return error(409, "space_has_locked")

                    !Regex("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(handle) ->
                        return error(400, "invalid_request", field = "handle")

                    published.any { (id, p) -> id != space.id && p.handle == handle } ->
                        return error(409, "handle_taken", field = "handle")

                    origin == null -> return error(400, "invalid_request", field = "origin")
                }
                val settings = PublishSettings(handle, "$origin/@$handle/", origin, published = true)
                published[space.id] = settings
                spaces[space.id] = withPublished(space, settings)
                ok(json.encodeToString(PublishSettings.serializer(), settings))
            }

            r.method == "DELETE" && parts.size == 3 && parts[0] == "spaces" && parts[2] == "publish" -> {
                val settings = published[parts[1]] ?: return error(404, "not_published")
                published[parts[1]] = settings.copy(published = false)
                spaces[parts[1]] = withPublished(spaces.getValue(parts[1]), null)
                MockResponse.Builder().code(204).build()
            }

            r.method == "POST" && path.endsWith("/email") -> ok("""{"id":"e1"}""")

            r.method == "GET" && path == "/settings/email" ->
                ok("""{"configured":true,"from":"Me <notes@example.com>","keyHint":"6789","editable":true}""")

            else -> error(404, "no_such_endpoint")
        }
    }

    private fun page(feed: String): MockResponse {
        val notes = feeds[feed].orEmpty()
        val page = SyncPage(notes, notes.maxOfOrNull { it.changeSeq } ?: 0, false, "g-$feed")
        return ok(json.encodeToString(SyncPage.serializer(), page))
    }

    /** [space] with [people], and its `shared` trait from [me]'s side, as the server works it out. */
    private fun shared(space: RemoteSpace, people: SharedSpace): RemoteSpace {
        val others = people.members.filter { it.id != me.id }.map { it.username }
        val pending = people.invites.map { it.username }
        val summary = when {
            others.isEmpty() -> "Invited ${names(pending)}"
            pending.isEmpty() -> "With ${names(others)}"
            else -> "With ${names(others)} · invited ${names(pending)}"
        }
        val on = others.isNotEmpty() || pending.isNotEmpty()
        val trait = SpaceTrait("shared", "Shared", summary, exposure = "Visible to ${names(others + pending)}")
        val seen = if (people.isOwner) people else people.copy(invites = emptyList())
        val rest = space.traits.filter { it.key != "shared" }
        return space.copy(shared = seen, traits = listOfNotNull(trait.takeIf { on }) + rest)
    }

    /** [space] with its `published` trait for [settings], or without one, after its other traits (§7.8's order). */
    fun withPublished(space: RemoteSpace, settings: PublishSettings?): RemoteSpace {
        val at = settings?.let { "${it.origin.substringAfter("://")}/@${it.handle}" }
        val trait = at?.let {
            SpaceTrait("published", "Published", "At $it", exposure = "Public at $it", audience = listOf("public"))
        }
        return space.copy(traits = space.traits.filter { it.key != "published" } + listOfNotNull(trait))
    }

    private fun ok(body: String) =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private fun error(code: Int, error: String, field: String? = null) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json")
        .body("""{"error":"$error","message":"$error"${field?.let { ""","field":"$it"""" }.orEmpty()}}""")
        .build()

    override fun close() = server.close()
}
