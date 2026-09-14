package dev.klerkframework.graphql

import com.fasterxml.jackson.databind.ObjectMapper
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.view.ModelViews
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

/** How props values reach the client, in particular the ones a scalar field cannot carry. */
class SerializationTest {

    private val mapper = ObjectMapper()

    private fun klerk(): Klerk<Context, MyViews> {
        val bc = BookCollections()
        val views = MyViews(bc, AuthorCollections(bc.all), ModelViews())
        return Klerk.create(createSpecification(views), testSettings())
    }

    private fun setup(builder: ApplicationTestBuilder, klerk: Klerk<Context, MyViews>, context: Context) =
        with(builder) {
            application {
                installKlerkGraphQL(klerk) { _: GraphQLContext -> context }
                routing { klerkGraphQLRoutes() }
            }
        }

    private suspend fun ApplicationTestBuilder.graphql(query: String): Map<*, *> {
        val response = client.post("/graphql") {
            contentType(ContentType.Application.Json)
            setBody(mapper.writeValueAsString(mapOf("query" to query)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return mapper.readValue(response.bodyAsText(), Map::class.java)
    }

    private val booksQuery = """
        { books(viewId: "v.Book.all", first: 1) {
            edges { node { props { title averageScore writtenAt } } }
        } }
    """.trimIndent()

    private fun Map<*, *>.firstBookProps(): Map<*, *> {
        assertNull(this["errors"], "the query failed: ${this["errors"]}")
        val models = (this["data"] as Map<*, *>)["books"] as Map<*, *>
        val edges = models["edges"] as List<*>
        return ((edges.first() as Map<*, *>)["node"] as Map<*, *>)["props"] as Map<*, *>
    }

    @Test
    fun `a Float property keeps its value`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        createBookHarryPotter1(klerk, createAuthorJKRowling(klerk))
        setup(this, klerk, Context.system())

        // 3.5 survives as a JSON number, not as a string.
        assertEquals(3.5, graphql(booksQuery).firstBookProps()["averageScore"])
    }

    @Test
    fun `a denied property is null, whatever its type`() = testApplication {
        val klerk = klerk()
        klerk.meta.start()
        createBookHarryPotter1(klerk, createAuthorJKRowling(klerk))
        // unauthenticatedCannotRead{AverageScore,BookTitle} deny these two for this actor.
        setup(this, klerk, Context.unauthenticated())

        val props = graphql(booksQuery).firstBookProps()
        assertNull(props["averageScore"], "a denied Float must not fail the field")
        assertNull(props["title"], "a denied String must be null too, not the mask")
        assertNotNull(props["writtenAt"], "the properties that are allowed still arrive")
    }
}
