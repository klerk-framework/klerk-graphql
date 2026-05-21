package dev.klerkframework.graphql

import dev.klerkframework.graphql.models.CreateShop
import dev.klerkframework.graphql.models.CreateShopParams
import dev.klerkframework.graphql.models.FaxNumber
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.read.ModelModification.Created
import dev.klerkframework.klerk.read.ModelModification.Deleted
import dev.klerkframework.klerk.read.ModelModification.PropsUpdated
import dev.klerkframework.klerk.read.ModelModification.Transitioned
import graphql.GraphQLContext
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

fun main() {

    val log = KotlinLogging.logger {}
    log.info { "Starting" }
    val bc = BookCollections()
    val views = MyViews(bc, AuthorCollections(bc.all), ModelViews())
    val klerk = Klerk.create(createConfig(views))
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
            createShop(klerk)
        }

        val embeddedServer = io.ktor.server.engine.embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            installKlerkGraphQL(klerk, ::contextFactory)

            routing {
                klerkGraphQLRoutes()
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

private suspend fun createShop(klerk: Klerk<Context, MyViews>) {
    klerk.handle(Command(
        event = CreateShop,
        model = null,
        params = CreateShopParams(
            faxNumber = null
        )
    ),
        Context.system(),
        ProcessingOptions(CommandToken.simple())
    )
}
