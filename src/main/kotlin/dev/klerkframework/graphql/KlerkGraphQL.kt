package dev.klerkframework.graphql

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.QueryListCursor
import dev.klerkframework.klerk.collection.QueryOptions
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.*
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.Scalars
import graphql.language.StringValue
import graphql.schema.*
import graphql.schema.idl.SchemaPrinter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass
import kotlin.reflect.full.isSuperclassOf
import kotlin.reflect.full.memberProperties

private val jackson = ObjectMapper().registerKotlinModule()
private val graphQLKey = AttributeKey<GraphQL>("KlerkGraphQL")

/**
 * Configuration for the KlerkGraphQL Ktor plugin.
 */
public class KlerkGraphQLConfig<C : KlerkContext, V> {
    internal var klerk: Klerk<C, V>? = null
    internal var contextFactory: (suspend (GraphQLContext) -> C)? = null

    public fun klerk(klerk: Klerk<C, V>) {
        this.klerk = klerk
    }

    public fun contextFactory(factory: suspend (GraphQLContext) -> C) {
        this.contextFactory = factory
    }
}

/**
 * Installs the KlerkGraphQL plugin into a Ktor application.
 *
 * The GraphQL schema is generated dynamically from [Klerk.config.managedModels], so users do not
 * need to create per-model query classes. For each managed model type `Foo`, the schema will
 * automatically expose typed queries `foo(id)` and `foos(collectionId)` returning `FooKlerkModel`
 * objects with a strongly-typed `props: FooProps` field.
 *
 * Usage:
 * ```kotlin
 * install(KlerkGraphQL) {
 *     klerk(klerk)
 *     contextFactory { _ -> Context.unauthenticated() }
 * }
 * routing {
 *     klerkGraphQLRoutes()
 * }
 * ```
 */
public fun <C : KlerkContext, V> Application.installKlerkGraphQL(
    klerk: Klerk<C, V>,
    contextFactory: suspend (GraphQLContext) -> C
) {
    @Suppress("UNCHECKED_CAST")
    val graphQL = buildGraphQL(
        klerk as Klerk<KlerkContext, Any>,
        contextFactory as suspend (GraphQLContext) -> KlerkContext
    )
    attributes.put(graphQLKey, graphQL)
}

/**
 * Registers the GraphQL routes: POST/GET `/graphql`, GraphiQL at `/graphiql`, SDL at `/sdl`.
 */
public fun Route.klerkGraphQLRoutes() {
    val graphQL = application.attributes[graphQLKey]

    post("/graphql") {
        val body = call.receiveText()
        val request = jackson.readValue(body, GraphQLRequest::class.java)
        val inputBuilder = ExecutionInput.newExecutionInput()
            .query(request.query)
            .operationName(request.operationName)
            .variables(request.variables ?: emptyMap())
            .graphQLContext(emptyMap<Any, Any>())
        val execInput = inputBuilder.build()
        val result = graphQL.executeAsync(execInput).get()
        call.respondText(jackson.writeValueAsString(result.toSpecification()), ContentType.Application.Json)
    }

    get("/graphql") {
        val query = call.request.queryParameters["query"] ?: ""
        val inputBuilder = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(emptyMap<Any, Any>())
        val execInput = inputBuilder.build()
        val result = graphQL.executeAsync(execInput).get()
        call.respondText(jackson.writeValueAsString(result.toSpecification()), ContentType.Application.Json)
    }

    graphiQLRoute()

    get("/sdl") {
        val schema = application.attributes[graphQLKey].graphQLSchema
        call.respondText(SchemaPrinter().print(schema), ContentType.Text.Plain)
    }
}

private data class GraphQLRequest(
    val query: String,
    val operationName: String? = null,
    val variables: Map<String, Any>? = null
)

// ---------------------------------------------------------------------------
// Schema builder
// ---------------------------------------------------------------------------

private fun <C : KlerkContext, V> buildGraphQL(
    klerk: Klerk<C, V>,
    contextFactory: suspend (GraphQLContext) -> C
): GraphQL {
    val scalarMap = mutableMapOf<String, GraphQLScalarType>()
    val typeMap = mutableMapOf<String, GraphQLObjectType>()

    // Build a typed ObjectType for each managed model
    for (managed in klerk.config.managedModels) {
        val propsType = buildPropsType(managed.kClass, scalarMap)
        val modelType = buildModelObjectType(managed.kClass.simpleName!!, propsType)
        typeMap[managed.kClass.simpleName!!] = modelType
    }

    // Shared types
    val klerkCommandType = GraphQLObjectType.newObject().name("KlerkCommand")
        .field { it.name("name").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("parameters").type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("KlerkParameter")))) }
        .build()

    val klerkParameterType = GraphQLObjectType.newObject().name("KlerkParameter")
        .field { it.name("name").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("type").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("ofType").type(Scalars.GraphQLString) }
        .field { it.name("nullable").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)) }
        .field { it.name("required").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)) }
        .build()

    val klerkCollectionType = GraphQLObjectType.newObject().name("KlerkCollection")
        .field { it.name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("type").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .build()

    val klerkFieldType = GraphQLObjectType.newObject().name("KlerkField")
        .field { it.name("name").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("type").type(Scalars.GraphQLString) }
        .field { it.name("value").type(Scalars.GraphQLString) }
        .build()

    val genericModelType = GraphQLObjectType.newObject().name("KlerkModel")
        .field { it.name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("type").type(Scalars.GraphQLString) }
        .field { it.name("state").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("createdAt").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("lastModifiedAt").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("lastPropsModifiedAt").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("lastStateTransitionAt").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("props").type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("KlerkField")))) }
        .field { it.name("possibleEvents").type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("KlerkCommand")))) }
        .build()

    val pageInfoType = GraphQLObjectType.newObject().name("PageInfo")
        .field { it.name("hasPreviousPage").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)) }
        .field { it.name("hasNextPage").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)) }
        .field { it.name("startCursor").type(Scalars.GraphQLString) }
        .field { it.name("endCursor").type(Scalars.GraphQLString) }
        .build()

    val klerkEdgeType = GraphQLObjectType.newObject().name("KlerkEdge")
        .field { it.name("node").type(GraphQLTypeReference("KlerkModel")) }
        .field { it.name("cursor").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .build()

    val klerkModelsResponseType = GraphQLObjectType.newObject().name("KlerkModelsResponse")
        .field { it.name("edges").type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("KlerkEdge")))) }
        .field { it.name("pageInfo").type(GraphQLNonNull.nonNull(GraphQLTypeReference("PageInfo"))) }
        .build()

    val createCommandResponseType = GraphQLObjectType.newObject().name("CreateCommandResponse")
        .field { it.name("createdModels").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
        .field { it.name("modifiedModels").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
        .field { it.name("deletedModels").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
        .field { it.name("generatedJobs").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
        .field { it.name("secondaryEvents").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
        .build()

    // Build Query type
    val queryBuilder = GraphQLObjectType.newObject().name("Query")

    // Generic queries
    queryBuilder.field { f ->
        f.name("collections")
            .type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("KlerkCollection"))))
            .dataFetcher { collectionsDataFetcher(klerk) }
    }
    queryBuilder.field { f ->
        f.name("models")
            .type(GraphQLTypeReference("KlerkModelsResponse"))
            .argument { it.name("collectionId").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .argument { it.name("first").type(Scalars.GraphQLInt) }
            .argument { it.name("after").type(Scalars.GraphQLString) }
            .argument { it.name("before").type(Scalars.GraphQLString) }
            .dataFetcher { env -> runBlocking { modelsDataFetcher(klerk, contextFactory, env) } }
    }
    queryBuilder.field { f ->
        f.name("model")
            .type(GraphQLTypeReference("KlerkModel"))
            .argument { it.name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .dataFetcher { env -> runBlocking { modelDataFetcher(klerk, contextFactory, env) } }
    }
    queryBuilder.field { f ->
        f.name("voidCommands")
            .type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("KlerkCommand"))))
            .argument { it.name("type").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .dataFetcher { env -> runBlocking { voidCommandsDataFetcher(klerk, contextFactory, env) } }
    }

    // Per-model typed queries: e.g. author(id) and authors(collectionId)
    for (managed in klerk.config.managedModels) {
        val typeName = managed.kClass.simpleName!!
        val singularName = typeName.replaceFirstChar { it.lowercase() }
        val pluralName = "${singularName}s"
        val modelTypeName = "${typeName}KlerkModel"
        val kClass = managed.kClass

        queryBuilder.field { f ->
            f.name(singularName)
                .type(GraphQLTypeReference(modelTypeName))
                .argument { it.name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
                .dataFetcher { env -> runBlocking { typedModelDataFetcher(klerk, contextFactory, kClass, env) } }
        }
        queryBuilder.field { f ->
            f.name(pluralName)
                .type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference(modelTypeName))))
                .argument { it.name("collectionId").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
                .argument { it.name("first").type(Scalars.GraphQLInt) }
                .argument { it.name("after").type(Scalars.GraphQLString) }
                .argument { it.name("before").type(Scalars.GraphQLString) }
                .dataFetcher { env -> runBlocking { typedModelsDataFetcher(klerk, contextFactory, kClass, env) } }
        }
    }

    // Mutation type
    val mutationType = GraphQLObjectType.newObject().name("Mutation")
        .field { f ->
            f.name("createCommand")
                .type(GraphQLTypeReference("CreateCommandResponse"))
                .argument { it.name("event").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
                .argument { it.name("model").type(Scalars.GraphQLString) }
                .argument { it.name("paramsJson").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
                .argument { it.name("dryRun").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)) }
                .dataFetcher { env -> runBlocking { createCommandDataFetcher(klerk, contextFactory, env) } }
        }
        .build()

    // Assemble schema
    val schemaBuilder = GraphQLSchema.newSchema()
        .query(queryBuilder.build())
        .mutation(mutationType)

    // Register all additional types
    val additionalTypes = mutableSetOf<GraphQLType>()
    additionalTypes.addAll(typeMap.values)
    additionalTypes.add(klerkCommandType)
    additionalTypes.add(klerkParameterType)
    additionalTypes.add(klerkCollectionType)
    additionalTypes.add(klerkFieldType)
    additionalTypes.add(genericModelType)
    additionalTypes.add(pageInfoType)
    additionalTypes.add(klerkEdgeType)
    additionalTypes.add(klerkModelsResponseType)
    additionalTypes.add(createCommandResponseType)
    additionalTypes.addAll(scalarMap.values)

    schemaBuilder.additionalTypes(additionalTypes)

    return GraphQL.newGraphQL(schemaBuilder.build()).build()
}

// ---------------------------------------------------------------------------
// Per-model typed ObjectType builders
// ---------------------------------------------------------------------------

private fun buildPropsType(kClass: KClass<*>, scalarMap: MutableMap<String, GraphQLScalarType>): GraphQLObjectType {
    val typeName = "${kClass.simpleName!!}Props"
    val builder = GraphQLObjectType.newObject().name(typeName)
    for (prop in kClass.memberProperties) {
        val fieldType = resolveGraphQLType(prop.returnType.classifier as? KClass<*>, scalarMap) ?: continue
        builder.field { f ->
            f.name(prop.name).type(fieldType).dataFetcher { env ->
                val obj = env.getSource<Any>()
                try {
                    @Suppress("UNCHECKED_CAST")
                    val rawValue = (prop as kotlin.reflect.KProperty1<Any, Any?>).get(obj!!)
                    serializeValue(rawValue)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    return builder.build()
}

private fun buildModelObjectType(typeName: String, propsType: GraphQLObjectType): GraphQLObjectType {
    val modelTypeName = "${typeName}KlerkModel"
    return GraphQLObjectType.newObject().name(modelTypeName)
        .field { it.name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("type").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("state").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("createdAt").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("lastModifiedAt").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("lastPropsModifiedAt").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("lastStateTransitionAt").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("props").type(GraphQLNonNull.nonNull(propsType)) }
        .field { it.name("possibleEvents").type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("KlerkCommand")))) }
        .build()
}

// ---------------------------------------------------------------------------
// Type resolution helpers
// ---------------------------------------------------------------------------

private fun resolveGraphQLType(kClass: KClass<*>?, scalarMap: MutableMap<String, GraphQLScalarType>): GraphQLOutputType? {
    if (kClass == null) return Scalars.GraphQLString
    return when {
        kClass == String::class -> Scalars.GraphQLString
        kClass == Int::class || kClass == java.lang.Integer::class -> Scalars.GraphQLInt
        kClass == Long::class || kClass == java.lang.Long::class -> Scalars.GraphQLString
        kClass == Float::class || kClass == Double::class -> Scalars.GraphQLFloat
        kClass == Boolean::class -> Scalars.GraphQLBoolean
        kClass == kotlin.time.Instant::class -> getOrCreateScalar("Instant", Scalars.GraphQLString, scalarMap) { (it as kotlin.time.Instant).toString() }
        kClass == kotlin.time.Duration::class -> getOrCreateScalar("Duration", Scalars.GraphQLString, scalarMap) { it.toString() }
        kClass == ULong::class -> getOrCreateScalar("ULong", Scalars.GraphQLString, scalarMap) { it.toString() }
        kClass == UInt::class -> getOrCreateScalar("UInt", Scalars.GraphQLString, scalarMap) { it.toString() }
        StringContainer::class.isSuperclassOf(kClass) -> getOrCreateScalar(kClass.simpleName!!, Scalars.GraphQLString, scalarMap) { (it as StringContainer).valueWithoutAuthorization }
        IntContainer::class.isSuperclassOf(kClass) -> getOrCreateScalar(kClass.simpleName!!, Scalars.GraphQLInt, scalarMap) { (it as IntContainer).valueWithoutAuthorization }
        LongContainer::class.isSuperclassOf(kClass) -> getOrCreateScalar(kClass.simpleName!!, Scalars.GraphQLString, scalarMap) { it.toString() }
        FloatContainer::class.isSuperclassOf(kClass) -> getOrCreateScalar(kClass.simpleName!!, Scalars.GraphQLFloat, scalarMap) { (it as FloatContainer).valueWithoutAuthorization }
        BooleanContainer::class.isSuperclassOf(kClass) -> getOrCreateScalar(kClass.simpleName!!, Scalars.GraphQLBoolean, scalarMap) { (it as BooleanContainer).valueWithoutAuthorization }
        InstantContainer::class.isSuperclassOf(kClass) -> getOrCreateScalar(kClass.simpleName!!, Scalars.GraphQLString, scalarMap) { (it as InstantContainer).instant.toString() }
        DurationContainer::class.isSuperclassOf(kClass) -> getOrCreateScalar(kClass.simpleName!!, Scalars.GraphQLString, scalarMap) { (it as DurationContainer).duration.toString() }
        EnumContainer::class.isSuperclassOf(kClass) -> getOrCreateScalar(kClass.simpleName!!, Scalars.GraphQLString, scalarMap) { (it as EnumContainer<*>).valueWithoutAuthorization.toString() }
        kClass.java.isEnum -> Scalars.GraphQLString
        ModelID::class.isSuperclassOf(kClass) -> Scalars.GraphQLString
        else -> Scalars.GraphQLString
    }
}

private fun getOrCreateScalar(
    name: String,
    base: GraphQLScalarType,
    scalarMap: MutableMap<String, GraphQLScalarType>,
    serialize: (Any) -> Any?
): GraphQLScalarType {
    return scalarMap.getOrPut(name) {
        GraphQLScalarType.newScalar(base).name(name).coercing(object : Coercing<Any, Any> {
            override fun serialize(dataFetcherResult: Any): Any? = serialize(dataFetcherResult)
            override fun parseValue(input: Any): Any = input
            override fun parseLiteral(input: Any): Any = (input as? StringValue)?.value ?: input
        }).build()
    }
}

private fun serializeValue(value: Any?): Any? {
    if (value == null) return null
    return when (value) {
        is StringContainer -> try { value.valueWithoutAuthorization } catch (e: Exception) { null }
        is IntContainer -> try { value.valueWithoutAuthorization } catch (e: Exception) { null }
        is LongContainer -> try { value.valueWithoutAuthorization.toString() } catch (e: Exception) { null }
        is FloatContainer -> try { value.valueWithoutAuthorization } catch (e: Exception) { null }
        is BooleanContainer -> try { value.valueWithoutAuthorization } catch (e: Exception) { null }
        is InstantContainer -> try { value.instant.toString() } catch (e: Exception) { null }
        is DurationContainer -> try { value.duration.toString() } catch (e: Exception) { null }
        is EnumContainer<*> -> try { value.valueWithoutAuthorization.toString() } catch (e: Exception) { null }
        is kotlin.time.Instant -> value.toString()
        is kotlin.time.Duration -> value.toString()
        is ModelID<*> -> value.toString()
        is Enum<*> -> value.name
        is String -> value
        is Int -> value
        is Long -> value.toString()
        is Float -> value
        is Double -> value
        is Boolean -> value
        else -> value.toString()
    }
}

// ---------------------------------------------------------------------------
// Data fetchers
// ---------------------------------------------------------------------------

private fun <C : KlerkContext, V> collectionsDataFetcher(klerk: Klerk<C, V>): List<Map<String, Any>> {
    return klerk.config.getCollections().map { (type, collection) ->
        mapOf("id" to collection.getFullId().toString(), "type" to type.simpleName!!)
    }
}

private suspend fun <C : KlerkContext, V> modelsDataFetcher(
    klerk: Klerk<C, V>,
    contextFactory: suspend (GraphQLContext) -> C,
    env: DataFetchingEnvironment
): Map<String, Any?> {
    val context = contextFactory(env.graphQlContext)
    val collectionId = env.getArgument<String>("collectionId")!!
    val first = env.getArgument<Int?>("first") ?: 10
    val after = env.getArgument<String?>("after")
    val before = env.getArgument<String?>("before")
    require(!(after != null && before != null))
    var cursor = after?.let { QueryListCursor.fromString(it) }
    if (before != null) cursor = QueryListCursor.fromString(before!!)
    val collection = klerk.config.getCollection(CollectionId.from(collectionId))
    val result = klerk.read(context) { query(collection, QueryOptions(maxItems = first, cursor)) }
    val edges = result.items.map { item ->
        val possibleEvents = klerk.read(context) { getPossibleEvents(item.id) }
        val node = genericModelMap(item, possibleEvents, klerk)
        val edgeCursor = QueryListCursor(after = item.createdAt).toString()
        mapOf("node" to node, "cursor" to edgeCursor)
    }
    return mapOf(
        "edges" to edges,
        "pageInfo" to mapOf(
            "hasPreviousPage" to result.hasPreviousPage,
            "hasNextPage" to result.hasNextPage,
            "startCursor" to result.cursorFirst.toString(),
            "endCursor" to result.cursorLast.toString()
        )
    )
}

private suspend fun <C : KlerkContext, V> modelDataFetcher(
    klerk: Klerk<C, V>,
    contextFactory: suspend (GraphQLContext) -> C,
    env: DataFetchingEnvironment
): Map<String, Any?>? {
    val context = contextFactory(env.graphQlContext)
    val id = env.getArgument<String>("id")!!
    val model = klerk.read(context) { getOrNull(ModelID(id.toInt())) } ?: return null
    val events = klerk.read(context) { getPossibleEvents(model.id) }
    return genericModelMap(model, events, klerk)
}

private suspend fun <C : KlerkContext, V> voidCommandsDataFetcher(
    klerk: Klerk<C, V>,
    contextFactory: suspend (GraphQLContext) -> C,
    env: DataFetchingEnvironment
): List<Map<String, Any?>> {
    val context = contextFactory(env.graphQlContext)
    val type = env.getArgument<String>("type")
    val managed = klerk.config.managedModels.single { it.kClass.simpleName == type }
    return klerk.config.getPossibleVoidEvents(managed.kClass, context)
        .map { commandToMap(it, klerk.config.getParameters(it)) }
}

private suspend fun <C : KlerkContext, V> typedModelDataFetcher(
    klerk: Klerk<C, V>,
    contextFactory: suspend (GraphQLContext) -> C,
    kClass: KClass<*>,
    env: DataFetchingEnvironment
): Map<String, Any?>? {
    val context = contextFactory(env.graphQlContext)
    val id = env.getArgument<String>("id")!!
    val model = klerk.read(context) { getOrNull(ModelID(id.toInt())) } ?: return null
    if (model.props::class != kClass) return null
    val events = klerk.read(context) { getPossibleEvents(model.id) }
    return typedModelMap(model, events, klerk)
}

private suspend fun <C : KlerkContext, V> typedModelsDataFetcher(
    klerk: Klerk<C, V>,
    contextFactory: suspend (GraphQLContext) -> C,
    kClass: KClass<*>,
    env: DataFetchingEnvironment
): List<Map<String, Any?>> {
    val context = contextFactory(env.graphQlContext)
    val collectionId = env.getArgument<String>("collectionId")!!
    val first = env.getArgument<Int?>("first") ?: 10
    val after = env.getArgument<String?>("after")
    val before = env.getArgument<String?>("before")
    require(!(after != null && before != null))
    var cursor = after?.let { QueryListCursor.fromString(it) }
    if (before != null) cursor = QueryListCursor.fromString(before!!)
    val collection = klerk.config.getCollection(CollectionId.from(collectionId))
    val result = klerk.read(context) { query(collection, QueryOptions(maxItems = first, cursor)) }
    return result.items.map { item ->
        val possibleEvents = klerk.read(context) { getPossibleEvents(item.id) }
        typedModelMap(item, possibleEvents, klerk)
    }
}

private fun <C : KlerkContext, V> typedModelMap(
    model: Model<out Any>,
    eventReferences: Set<EventReference>,
    klerk: Klerk<C, V>
): Map<String, Any?> {
    val commands = eventReferences.map { commandToMap(it, klerk.config.getParameters(it)) }
    return mapOf(
        "id" to model.id.toString(),
        "type" to (model.props::class.simpleName ?: ""),
        "state" to model.state,
        "createdAt" to model.createdAt.toString(),
        "lastModifiedAt" to model.lastModifiedAt.toString(),
        "lastPropsModifiedAt" to model.lastPropsUpdateAt.toString(),
        "lastStateTransitionAt" to model.lastStateTransitionAt.toString(),
        "props" to model.props,
        "possibleEvents" to commands
    )
}

private fun <C : KlerkContext, V> genericModelMap(
    model: Model<out Any>,
    eventReferences: Set<EventReference>,
    klerk: Klerk<C, V>
): Map<String, Any?> {
    val props = model.props::class.memberProperties.map { prop ->
        val value = try {
            @Suppress("UNCHECKED_CAST")
            (prop as kotlin.reflect.KProperty1<Any, *>).get(model.props)
        } catch (e: Exception) { null }
        mapOf(
            "name" to prop.name,
            "type" to (prop.returnType.classifier as? KClass<*>)?.simpleName,
            "value" to serializeValue(value)
        )
    }
    val commands = eventReferences.map { commandToMap(it, klerk.config.getParameters(it)) }
    return mapOf(
        "id" to model.id.toString(),
        "type" to model.props::class.simpleName,
        "state" to model.state,
        "createdAt" to model.createdAt.toString(),
        "lastModifiedAt" to model.lastModifiedAt.toString(),
        "lastPropsModifiedAt" to model.lastPropsUpdateAt.toString(),
        "lastStateTransitionAt" to model.lastStateTransitionAt.toString(),
        "props" to props,
        "possibleEvents" to commands
    )
}

private fun commandToMap(
    ref: EventReference,
    parameters: dev.klerkframework.klerk.misc.EventParameters<*>?
): Map<String, Any?> {
    val params = parameters?.all?.map { p ->
        mapOf(
            "name" to p.name,
            "type" to (p.type?.name ?: "[?]"),
            "ofType" to p.modelIDType,
            "nullable" to p.isNullable,
            "required" to p.isRequired
        )
    } ?: emptyList()
    return mapOf("name" to ref.toString(), "parameters" to params)
}

private suspend fun <C : KlerkContext, V> createCommandDataFetcher(
    klerk: Klerk<C, V>,
    contextFactory: suspend (GraphQLContext) -> C,
    env: DataFetchingEnvironment
): Map<String, Any?> {
    val context = contextFactory(env.graphQlContext)
    val event = env.getArgument<String>("event")!!
    val modelArg = env.getArgument<String?>("model")
    val paramsJson = env.getArgument<String>("paramsJson")!!
    val dryRun = env.getArgument<Boolean>("dryRun")!!

    val eventObj = klerk.config.getEvent(EventReference.from(event))
    val parameterInfo = klerk.config.getParameters(eventObj.id)
    val paramsObject = if (parameterInfo == null) null else
        klerk.config.fromJson(paramsJson, parameterInfo.raw.javaObjectType)

    val result = klerk.handle(
        Command(
            event = eventObj,
            model = if (modelArg == null) null else ModelID(modelArg.toInt()),
            params = paramsObject,
        ),
        context,
        ProcessingOptions(CommandToken.simple(), dryRun = dryRun)
    )

    return when (result) {
        is CommandResult.Success -> mapOf(
            "createdModels" to result.createdModels.map { it.toString() },
            "modifiedModels" to result.modelsWithUpdatedProps.map { it.toString() },
            "deletedModels" to result.deletedModels.map { it.toString() },
            "generatedJobs" to result.jobs.map { it.id?.toUInt().toString() },
            "secondaryEvents" to result.secondaryEvents.map { it.id() }
        )
        is CommandResult.Failure -> {
            val message = requireNotNull(result.problems.first()).endUserTranslatedMessage
            throw graphql.GraphqlErrorException.newErrorException().message(message).build()
        }
    }
}

public fun Route.graphiQLRoute(
    endpoint: String = "graphiql",
    graphQLEndpoint: String = "graphql",
    subscriptionsEndpoint: String = "subscriptions",
): Route {
    val contextPath = this.application.rootPath
    val graphiQL = GraphQL::class.java.classLoader.getResourceAsStream("graphql-graphiql.html")?.bufferedReader()?.use { reader ->
        reader.readText()
            .replace("\${graphQLEndpoint}", if (contextPath.isBlank()) graphQLEndpoint else "$contextPath/$graphQLEndpoint")
            .replace("\${subscriptionsEndpoint}", if (contextPath.isBlank()) subscriptionsEndpoint else "$contextPath/$subscriptionsEndpoint")
    } ?: throw IllegalStateException("Unable to load GraphiQL")
    return get(endpoint) {
        call.respondText(graphiQL, ContentType.Text.Html)
    }
}
