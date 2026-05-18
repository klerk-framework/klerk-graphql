# Klerk-graphql

Klerk-graphql generates a GraphQL API for your [Klerk](https://klerkframework.dev/) application. 
It is based on [graphql-java](https://github.com/graphql-java/graphql-java)

# Installation

```kotlin
implementation("com.github.klerk-framework:klerk-graphql:$klerk_graphql_version")
```

## Usage

Install the plugin:
```kotlin
embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = {
    installKlerkGraphQL(klerk, ::graphQlContextProvider)
    routing {
        klerkGraphQLRoutes()
    }
}).start(wait = true)
```

Create a function to create a context:
```kotlin
suspend fun graphQlContextProvider(graphQlContext: GraphQLContext): Ctx {
    // create context here 
}
```

You can now browse to /graphiql to explore the API.
