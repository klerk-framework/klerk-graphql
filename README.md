# Klerk-graphql

Klerk-graphql generates a GraphQL API for your [Klerk](https://klerkframework.dev/) application.
It should be used together with [GraphQL Kotlin](https://opensource.expediagroup.com/graphql-kotlin/docs/server/graphql-server/) and [Ktor](https://ktor.io).

# Installation

Add graphql-kotlin-ktor-server and klerk-graphql to your project:

```kotlin
implementation("com.expediagroup:graphql-kotlin-ktor-server:$graphql_version")
implementation("com.github.klerk-framework:klerk-graphql:$klerk_graphql_version")
```

For details on how to set up Klerk, see the [Klerk documentation](https://klerkframework.dev/docs/intro).

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
    // as described in https://klerkframework.dev/docs/building-config/context a Context is always required when 
    // interacting with Klerk. This function tells Klerk-web how to get this Context.  
}
```

Now use graphql-kotlin-ktor-server as normal: 
```kotlin
routing {
    graphQLPostRoute()
    graphQLGetRoute()
    graphiQLRoute()
    graphQLSDLRoute()
}
```

You can now browse to /graphiql to explore the API.

# Examples
To learn more how Klerk-graphql can be used, see the [examples](https://github.com/search?q=org%3Aklerk-framework+example&type=repositories). 
