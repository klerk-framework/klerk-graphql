package dev.klerkframework.graphql

import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.read.ModelModification.Created
import dev.klerkframework.klerk.read.ModelModification.Deleted
import dev.klerkframework.klerk.read.ModelModification.PropsUpdated
import dev.klerkframework.klerk.read.ModelModification.Transitioned
import com.expediagroup.graphql.server.ktor.DefaultKtorGraphQLContextFactory
import com.expediagroup.graphql.server.ktor.GraphQL
import com.expediagroup.graphql.server.ktor.graphQLGetRoute
import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import com.expediagroup.graphql.server.ktor.graphQLSDLRoute
import com.expediagroup.graphql.server.ktor.graphiQLRoute
import graphql.GraphQLContext
import io.ktor.server.application.install
import io.ktor.server.netty.Netty
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import com.expediagroup.graphql.generator.scalars.ID
import dev.klerkframework.graphql.models.Author
import dev.klerkframework.graphql.models.Book
import mu.KotlinLogging

class AuthorModel(
    id: ID, type: String, state: String, createdAt: String,
    lastModifiedAt: String, lastPropsModifiedAt: String, lastStateTransitionAt: String,
    props: Author, possibleEvents: List<KlerkCommand>
) : TypedKlerkModel<Author>(id, type, state, createdAt, lastModifiedAt, lastPropsModifiedAt, lastStateTransitionAt, props, possibleEvents)

class BookModel(
    id: ID, type: String, state: String, createdAt: String,
    lastModifiedAt: String, lastPropsModifiedAt: String, lastStateTransitionAt: String,
    props: Book, possibleEvents: List<KlerkCommand>
) : TypedKlerkModel<Book>(id, type, state, createdAt, lastModifiedAt, lastPropsModifiedAt, lastStateTransitionAt, props, possibleEvents)

class AuthorQuery(
    klerk: Klerk<Context, MyCollections>,
    contextFactory: suspend (graphql.GraphQLContext) -> Context
) : TypedKlerkQueryService<Context, MyCollections, Author, AuthorModel>(klerk, contextFactory, ::AuthorModel) {
    suspend fun author(id: com.expediagroup.graphql.generator.scalars.ID, env: graphql.schema.DataFetchingEnvironment): AuthorModel? = model(id, env)
    suspend fun authors(collectionId: String, first: Int? = 10, after: String? = null, before: String? = null, env: graphql.schema.DataFetchingEnvironment): List<AuthorModel> = models(collectionId, first, after, before, env)
}

class BookQuery(
    klerk: Klerk<Context, MyCollections>,
    contextFactory: suspend (graphql.GraphQLContext) -> Context
) : TypedKlerkQueryService<Context, MyCollections, Book, BookModel>(klerk, contextFactory, ::BookModel) {
    suspend fun book(id: com.expediagroup.graphql.generator.scalars.ID, env: graphql.schema.DataFetchingEnvironment): BookModel? = model(id, env)
    suspend fun books(collectionId: String, first: Int? = 10, after: String? = null, before: String? = null, env: graphql.schema.DataFetchingEnvironment): List<BookModel> = models(collectionId, first, after, before, env)
}

fun main() {

    val log = KotlinLogging.logger {}
    log.info { "Starting" }
    val bc = BookCollections()
    val collections = MyCollections(bc, AuthorCollections(bc.all))
    val klerk = Klerk.create(createConfig(collections))
    runBlocking {
        klerk.meta.start()

        if (klerk.meta.modelsCount == 0) {
            createAuthorJKRowling(klerk)
            createBookHarryPotter1(klerk, createAuthorJKRowling(klerk))
            createBookHarryPotter2(
                klerk,
                createAuthorJKRowling(klerk),
                listOf(createBookHarryPotter1(klerk, createAuthorJKRowling(klerk))),
                setOf(createAuthorJKRowling(klerk))
            )
        }

        val embeddedServer = io.ktor.server.engine.embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            install(GraphQL) {
                schema {
                    packages = listOf("dev.klerkframework.graphql", "dev.klerkframework.graphql.models")
                    hooks = KlerkSchemaGeneratorHooks()
                    queries = listOf(
                        GenericQuery(klerk, ::contextFactory),
                        AuthorQuery(klerk, ::contextFactory),
                        BookQuery(klerk, ::contextFactory),
                    )
                    mutations = listOf(EventMutationService<Context, MyCollections>(klerk, ::contextFactory))
                }
                server {
                    contextFactory = CustomGraphQLContextFactory()
                }
            }

            routing {
                graphQLPostRoute()
                graphQLGetRoute()
                graphiQLRoute()
                graphQLSDLRoute()
            }
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            println("Shutting down")
            embeddedServer.stop()
            klerk.meta.stop()
            println("Shutdown complete")
        })

        embeddedServer.start(wait = false)

        klerk.models.subscribe(Context.system(), null).collect {
            when (it) {
                is Created -> println("${it.id} was created")
                is PropsUpdated -> println("${it.id} had props updated")
                is Transitioned -> println("${it.id} transitioned")
                is Deleted -> println("${it.id} was deleted")
            }
        }
    }

}

private fun contextFactory(graphQlContext: GraphQLContext) = Context.unauthenticated()

class CustomGraphQLContextFactory : DefaultKtorGraphQLContextFactory() {
    override suspend fun generateContext(request: ApplicationRequest): GraphQLContext {
        return super.generateContext(request)
    }
}
