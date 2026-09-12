package dev.klerkframework.graphql

import com.fasterxml.jackson.databind.ObjectMapper
import dev.klerkframework.graphql.models.Author
import dev.klerkframework.graphql.models.CreateAuthor
import dev.klerkframework.graphql.models.CreateAuthorParams
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import graphql.GraphQLContext
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Relay-style pagination over the generated schema. */
class ConnectionTest {

    private val mapper = ObjectMapper()

    private fun klerk(): Klerk<Context, MyViews> {
        val bc = BookCollections()
        val views = MyViews(bc, AuthorCollections(bc.all), ModelViews())
        return Klerk.create(createSpecification(views), testSettings())
    }

    private suspend fun createAuthor(klerk: Klerk<Context, MyViews>, first: String, last: String): ModelID<Author> =
        klerk.handle(
            Command(
                event = CreateAuthor,
                model = null,
                params = CreateAuthorParams(
                    firstName = FirstName(first),
                    lastName = LastName(last),
                    phone = PhoneNumber("+46123456"),
                    secretToken = SecretPasscode(1),
                ),
            ),
            Context.system(),
        ).getOrThrow().primaryModel!!

    private suspend fun ApplicationTestBuilder.graphql(query: String): Map<*, *> {
        val response = client.post("/graphql") {
            contentType(ContentType.Application.Json)
            setBody(mapper.writeValueAsString(mapOf("query" to query)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return mapper.readValue(response.bodyAsText(), Map::class.java)
    }

    private fun Map<*, *>.dataAt(vararg path: String): Any? {
        assertNull(this["errors"], "the query failed: ${this["errors"]}")
        var current: Any? = this["data"]
        path.forEach { current = (current as Map<*, *>)[it] }
        return current
    }

    private fun edgesOf(connection: Any?): List<Map<*, *>> {
        @Suppress("UNCHECKED_CAST")
        return (connection as Map<*, *>)["edges"] as List<Map<*, *>>
    }

    private fun namesOf(connection: Any?): List<String> =
        edgesOf(connection).map { edge ->
            val node = edge["node"] as Map<*, *>
            (node["props"] as Map<*, *>)["lastName"] as String
        }

    private fun pageInfo(connection: Any?): Map<*, *> = (connection as Map<*, *>)["pageInfo"] as Map<*, *>

    private fun setup(builder: ApplicationTestBuilder, klerk: Klerk<Context, MyViews>) = with(builder) {
        application {
            installKlerkGraphQL(klerk) { _: GraphQLContext -> Context.system() }
            routing { klerkGraphQLRoutes() }
        }
    }

    private fun authorsQuery(args: String) = """
        { authors(collectionId: "c.Author.all"$args) {
            totalCount
            edges { cursor node { props { lastName } } }
            pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        } }
    """.trimIndent()

    @Test
    fun `first and after walk the whole collection`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        (0 until 23).forEach { createAuthor(klerk, "Kalle", "%03d".format(it)) }
        setup(this, klerk)

        val seen = mutableListOf<String>()
        var args = ", first: 10"
        var pages = 0
        while (true) {
            val connection = graphql(authorsQuery(args)).dataAt("authors")
            seen.addAll(namesOf(connection))
            pages++
            assertTrue(pages < 20)
            assertEquals(23, (connection as Map<*, *>)["totalCount"])
            if (pageInfo(connection)["hasNextPage"] != true) break
            args = ", first: 10, after: \"${pageInfo(connection)["endCursor"]}\""
        }
        assertEquals(3, pages)
        assertEquals((0 until 23).map { "%03d".format(it) }, seen)
    }

    @Test
    fun `startCursor and endCursor are the first and last edge of the page`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        (0 until 12).forEach { createAuthor(klerk, "Kalle", "%03d".format(it)) }
        setup(this, klerk)

        val connection = graphql(authorsQuery(", first: 5")).dataAt("authors")
        val edges = edgesOf(connection)
        assertEquals(5, edges.size)
        assertEquals(edges.first()["cursor"], pageInfo(connection)["startCursor"])
        assertEquals(edges.last()["cursor"], pageInfo(connection)["endCursor"])
        assertEquals(5, edges.map { it["cursor"] }.toSet().size, "every edge gets its own cursor")

        // An edge cursor is a position: reading after it starts at the next item.
        val afterSecond = graphql(authorsQuery(", first: 3, after: \"${edges[1]["cursor"]}\"")).dataAt("authors")
        assertEquals(listOf("002", "003", "004"), namesOf(afterSecond))
    }

    @Test
    fun `last and before page backwards`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        (0 until 20).forEach { createAuthor(klerk, "Kalle", "%03d".format(it)) }
        setup(this, klerk)

        val firstPage = graphql(authorsQuery(", first: 12")).dataAt("authors")
        val twelfth = edgesOf(firstPage).last()["cursor"]

        val backwards = graphql(authorsQuery(", last: 5, before: \"$twelfth\"")).dataAt("authors")
        assertEquals(listOf("006", "007", "008", "009", "010"), namesOf(backwards))
        assertEquals(true, pageInfo(backwards)["hasPreviousPage"])
        assertEquals(true, pageInfo(backwards)["hasNextPage"])
    }

    @Test
    fun `last without before is refused with a usable message`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        createAuthor(klerk, "Kalle", "001")
        setup(this, klerk)

        val result = graphql(authorsQuery(", last: 5"))
        val errors = result["errors"] as List<*>
        assertTrue(errors.isNotEmpty())
        assertTrue(
            errors.toString().contains("'last' needs 'before'"),
            "should explain what to do instead, was $errors",
        )
    }

    @Test
    fun `a filter is applied before the page is cut, so pages stay full`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        // Only every third author matches, so a naive post-filter would return 3 or 4 of the 10 asked for.
        (0 until 30).forEach { createAuthor(klerk, if (it % 3 == 0) "Bertil" else "Kalle", "%03d".format(it)) }
        setup(this, klerk)

        val connection = graphql(
            """
            { authors(collectionId: "c.Author.all", first: 5, where: {firstName: {_eq: "Bertil"}}) {
                totalCount
                edges { node { props { lastName firstName } } }
                pageInfo { hasNextPage }
            } }
            """.trimIndent()
        ).dataAt("authors")

        assertEquals(5, edgesOf(connection).size, "the page must be full")
        assertEquals(10, (connection as Map<*, *>)["totalCount"], "totalCount counts what matches the filter")
        assertTrue(edgesOf(connection).all { (((it["node"] as Map<*, *>)["props"] as Map<*, *>)["firstName"]) == "Bertil" })
    }

    @Test
    fun `the generic models field is a connection too`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        (0 until 8).forEach { createAuthor(klerk, "Kalle", "%03d".format(it)) }
        setup(this, klerk)

        val connection = graphql(
            """
            { models(collectionId: "c.Author.all", first: 3) {
                totalCount
                edges { cursor node { id state } }
                pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
            } }
            """.trimIndent()
        ).dataAt("models")

        assertEquals(3, edgesOf(connection).size)
        assertEquals(8, (connection as Map<*, *>)["totalCount"])
        assertEquals(true, pageInfo(connection)["hasNextPage"])
        assertEquals(false, pageInfo(connection)["hasPreviousPage"])
        assertNotNull(pageInfo(connection)["startCursor"])
    }

    @Test
    fun `a malformed cursor is an error, not a wrong page`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        (0 until 5).forEach { createAuthor(klerk, "Kalle", "%03d".format(it)) }
        setup(this, klerk)

        val result = graphql(authorsQuery(", first: 2, after: \"not-a-cursor\""))
        assertNotNull(result["errors"])
    }

    @Test
    fun `the schema exposes Connection and Edge types`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        setup(this, klerk)

        val sdl = client.get("/sdl").bodyAsText()
        assertTrue(sdl.contains("type AuthorConnection"), "expected AuthorConnection in the schema")
        assertTrue(sdl.contains("type AuthorEdge"), "expected AuthorEdge in the schema")
        assertTrue(sdl.contains("type KlerkModelConnection"), "expected KlerkModelConnection in the schema")
        assertTrue(sdl.contains("startCursor"))
    }
}
