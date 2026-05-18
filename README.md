# Klerk-graphql

Klerk-graphql generates a GraphQL API for your [Klerk](https://klerkframework.dev/) application.

# Installation

```kotlin
implementation("com.github.klerk-framework:klerk-graphql:$klerk_graphql_version")
```

## Usage

Install the GraphQL plugin, specifying the schema like this:
```kotlin
embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = {
    install(GraphQL) {
        schema {
            packages = listOf("dev.klerkframework.graphql")
            queries = listOf(GenericQuery(klerk, ::graphQlContextProvider))
            mutations = listOf(EventMutationService(klerk, ::graphQlContextProvider))
        }
    }
    // remaining configuration
}).start(wait = true)
```

Create a function to create a context:
```kotlin
suspend fun graphQlContextProvider(graphQlContext: GraphQLContext): Ctx {
    // create context here 
}
```

Register the routes:
```kotlin
routing {
    graphQLPostRoute()
    graphQLGetRoute()
    graphiQLRoute()
    graphQLSDLRoute()
}
```

You can now browse to /graphiql to explore the API.
