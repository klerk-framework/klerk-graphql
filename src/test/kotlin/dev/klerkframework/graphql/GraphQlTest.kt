package dev.klerkframework.graphql

import com.expediagroup.graphql.server.ktor.DefaultKtorGraphQLContextFactory
import com.expediagroup.graphql.server.ktor.GraphQL
import com.expediagroup.graphql.server.ktor.graphQLGetRoute
import com.expediagroup.graphql.server.ktor.graphQLPostRoute
import com.expediagroup.graphql.server.ktor.graphQLSDLRoute
import com.expediagroup.graphql.server.ktor.graphiQLRoute
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.read.ModelModification.Created
import dev.klerkframework.klerk.read.ModelModification.Deleted
import dev.klerkframework.klerk.read.ModelModification.PropsUpdated
import dev.klerkframework.klerk.read.ModelModification.Transitioned
import graphql.GraphQLContext
import io.ktor.server.application.install
import io.ktor.server.netty.Netty
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking

    /*
fun main() {


    val bc = BookCollections()
    val collections = MyCollections(bc, AuthorCollections(bc.all))
    val klerk = Klerk.create(createConfig(collections))
    runBlocking {


        klerk.meta.start()

        if (klerk.meta.modelsCount == 0) {
            generateSampleData(50, 2, klerk)
        }

        val embeddedServer = io.ktor.server.engine.embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            install(GraphQL) {
                schema {
                    packages = listOf("dev.klerkframework.klerk.graphql")
                    queries = listOf(GenericQuery(klerk, ::contextFactory))
                    mutations = listOf(EventMutationService(klerk, ::contextFactory))
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


     */