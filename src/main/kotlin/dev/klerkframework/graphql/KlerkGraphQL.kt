package dev.klerkframework.graphql

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.view.PageDirection
import dev.klerkframework.klerk.view.QueryListCursor
import dev.klerkframework.klerk.view.QueryOptions
import dev.klerkframework.klerk.view.QueryResponse
import dev.klerkframework.klerk.view.query
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
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.misc.SchemaField
import kotlin.reflect.KClass

private val jackson = ObjectMapper().registerKotlinModule()
private val graphQLKey = AttributeKey<GraphQL>("KlerkGraphQL")

/**
 * The key under which the request's [ApplicationCall] is stored in the [GraphQLContext] passed to `contextFactory`.
 * Read it with [GraphQLContext.applicationCall] rather than looking it up directly.
 */
public object ApplicationCallContextKey

/** The [ApplicationCall] behind this request, e.g. to read cookies or headers when building a [KlerkContext]. */
public fun GraphQLContext.applicationCall(): ApplicationCall? = get(ApplicationCallContextKey)

/**
 * Installs the KlerkGraphQL plugin into a Ktor application.
 *
 * The GraphQL schema is generated dynamically from [Klerk.specification.managedModels], so users do not
 * need to create per-model query classes. For each managed model type `Foo`, the schema will
 * automatically expose typed queries `foo(id)` and `foos(viewId)` returning `FooKlerkModel`
 * objects with a strongly-typed `props: FooProps` field.
 *
 * [contextFactory] receives the [GraphQLContext] of the request — use [GraphQLContext.applicationCall] to read
 * cookies/headers off the underlying [ApplicationCall] when building an authenticated context.
 *
 * Usage:
 * ```kotlin
 * installKlerkGraphQL(klerk) { graphQlContext -> Context.fromCall(graphQlContext.applicationCall()) }
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
            .graphQLContext(mapOf(ApplicationCallContextKey to call))
        val execInput = inputBuilder.build()
        val result = graphQL.executeAsync(execInput).get()
        call.respondText(jackson.writeValueAsString(result.toSpecification()), ContentType.Application.Json)
    }

    get("/graphql") {
        val query = call.request.queryParameters["query"] ?: ""
        val inputBuilder = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(mapOf(ApplicationCallContextKey to call))
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

private const val KLERK_META = "Model"

private fun <C : KlerkContext, V> buildGraphQL(
    klerk: Klerk<C, V>,
    contextFactory: suspend (GraphQLContext) -> C
): GraphQL {
    val scalarMap = mutableMapOf<String, GraphQLScalarType>()
    val enumTypeMap = mutableMapOf<String, GraphQLEnumType>()
    val typeMap = mutableMapOf<String, GraphQLObjectType>()

    // Build a typed ObjectType and WhereInput for each managed model
    val whereInputMap = mutableMapOf<String, GraphQLInputObjectType>()
    for (managed in klerk.specification.managedModels) {
        val propsType = buildPropsType(managed.kClass, scalarMap, enumTypeMap)
        val modelType = buildModelObjectType(managed.kClass.simpleName!!, propsType)
        typeMap[managed.kClass.simpleName!!] = modelType
        val whereInput = buildWhereInputType(managed.kClass)
        whereInputMap[managed.kClass.simpleName!!] = whereInput
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

    val genericModelType = GraphQLObjectType.newObject().name(KLERK_META)
        .field { it.name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("type").type(Scalars.GraphQLString) }
        .field { it.name("state").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("createdAt").type(GraphQLNonNull.nonNull(getOrCreateScalar("Instant", Scalars.GraphQLString, scalarMap) { (it as kotlin.time.Instant).toString() })) }
        .field { it.name("lastModifiedAt").type(GraphQLNonNull.nonNull(getOrCreateScalar("Instant", Scalars.GraphQLString, scalarMap) { (it as kotlin.time.Instant).toString() })) }
        .field { it.name("lastPropsModifiedAt").type(GraphQLNonNull.nonNull(getOrCreateScalar("Instant", Scalars.GraphQLString, scalarMap) { (it as kotlin.time.Instant).toString() })) }
        .field { it.name("lastStateTransitionAt").type(GraphQLNonNull.nonNull(getOrCreateScalar("Instant", Scalars.GraphQLString, scalarMap) { (it as kotlin.time.Instant).toString() })) }
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
        .field { it.name("node").type(GraphQLTypeReference(KLERK_META)) }
        .field { it.name("cursor").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .build()

    val klerkModelsResponseType = GraphQLObjectType.newObject().name("KlerkModelConnection")
        .field { it.name("edges").type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("KlerkEdge")))) }
        .field { it.name("pageInfo").type(GraphQLNonNull.nonNull(GraphQLTypeReference("PageInfo"))) }
        .field { it.name("totalCount").type(Scalars.GraphQLInt).description("The size of the whole collection.") }
        .build()

    val createCommandResponseType = GraphQLObjectType.newObject().name("CreateCommandResponse")
        .field { it.name("createdModels").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
        .field { it.name("modifiedModels").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
        .field { it.name("deletedModels").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
        .field { it.name("generatedJobs").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
        .build()

    val stringComparisonExpType = GraphQLInputObjectType.newInputObject().name("StringComparisonExp")
        .field { it.name("_eq").type(Scalars.GraphQLString) }
        .field { it.name("_neq").type(Scalars.GraphQLString) }
        .field { it.name("_gt").type(Scalars.GraphQLString) }
        .field { it.name("_lt").type(Scalars.GraphQLString) }
        .field { it.name("_gte").type(Scalars.GraphQLString) }
        .field { it.name("_lte").type(Scalars.GraphQLString) }
        .field { it.name("_like").type(Scalars.GraphQLString) }
        .field { it.name("_ilike").type(Scalars.GraphQLString) }
        .field { it.name("_in").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
        .field { it.name("_is_null").type(Scalars.GraphQLBoolean) }
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
            .type(GraphQLTypeReference("KlerkModelConnection"))
            .argument { it.name("viewId").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .argument { it.name("where").type(Scalars.GraphQLString).description("JSON-encoded where filter, e.g. '{\"firstName\":{\"_eq\":\"Adam\"}}'" ) }
            .also { addConnectionArguments(it) }
            .dataFetcher { env -> runBlocking { modelsDataFetcher(klerk, contextFactory, env) } }
    }
    queryBuilder.field { f ->
        f.name("model")
            .type(GraphQLTypeReference(KLERK_META))
            .argument { it.name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .dataFetcher { env -> runBlocking { modelDataFetcher(klerk, contextFactory, env) } }
    }
    queryBuilder.field { f ->
        f.name("voidCommands")
            .type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("KlerkCommand"))))
            .argument { it.name("type").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .dataFetcher { env -> runBlocking { voidCommandsDataFetcher(klerk, contextFactory, env) } }
    }

    // Per-model typed queries: e.g. author(id) and authors(viewId)
    val connectionTypes = mutableSetOf<GraphQLType>()
    for (managed in klerk.specification.managedModels) {
        val typeName = managed.kClass.simpleName!!
        val singularName = typeName.replaceFirstChar { it.lowercase() }
        val pluralName = "${singularName}s"
        val modelTypeName = "${typeName}Model"
        val kClass = managed.kClass
        val whereInputType = whereInputMap[typeName]!!

        queryBuilder.field { f ->
            f.name(singularName)
                .type(GraphQLTypeReference(modelTypeName))
                .argument { it.name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
                .dataFetcher { env -> runBlocking { typedModelDataFetcher(klerk, contextFactory, kClass, env) } }
        }
        queryBuilder.field { f ->
            f.name(pluralName)
                .type(GraphQLTypeReference("${typeName}Connection"))
                .argument { it.name("viewId").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
                .argument { it.name("state").type(stringComparisonExpType).description("Filter by model state") }
                .argument { it.name("createdAt").type(stringComparisonExpType).description("Filter by createdAt timestamp") }
                .argument { it.name("where").type(whereInputType).description("Filter on props") }
                .also { addConnectionArguments(it) }
                .dataFetcher { env -> runBlocking { typedModelsDataFetcher(klerk, contextFactory, kClass, env) } }
        }
        connectionTypes.add(
            GraphQLObjectType.newObject().name("${typeName}Edge")
                .field { it.name("node").type(GraphQLTypeReference(modelTypeName)) }
                .field { it.name("cursor").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
                .build()
        )
        connectionTypes.add(
            GraphQLObjectType.newObject().name("${typeName}Connection")
                .field {
                    it.name("edges").type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("${typeName}Edge"))))
                }
                .field { it.name("pageInfo").type(GraphQLNonNull.nonNull(GraphQLTypeReference("PageInfo"))) }
                .field { it.name("totalCount").type(Scalars.GraphQLInt).description("The size of the whole collection.") }
                .build()
        )
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
    additionalTypes.addAll(whereInputMap.values)
    additionalTypes.add(klerkCommandType)
    additionalTypes.add(klerkParameterType)
    additionalTypes.add(klerkCollectionType)
    additionalTypes.add(klerkFieldType)
    additionalTypes.add(genericModelType)
    additionalTypes.add(pageInfoType)
    additionalTypes.add(klerkEdgeType)
    additionalTypes.add(klerkModelsResponseType)
    additionalTypes.addAll(connectionTypes)
    additionalTypes.add(createCommandResponseType)
    additionalTypes.add(stringComparisonExpType)
    additionalTypes.addAll(scalarMap.values)
    additionalTypes.addAll(enumTypeMap.values)

    schemaBuilder.additionalTypes(additionalTypes)

    return GraphQL.newGraphQL(schemaBuilder.build()).build()
}

// ---------------------------------------------------------------------------
// Per-model typed ObjectType builders
// ---------------------------------------------------------------------------

private fun buildPropsType(kClass: KClass<*>, scalarMap: MutableMap<String, GraphQLScalarType>, enumTypeMap: MutableMap<String, GraphQLEnumType>): GraphQLObjectType {
    val typeName = "${kClass.simpleName!!}Props"
    val builder = GraphQLObjectType.newObject().name(typeName)
    for (field in ObjectSchema.of(kClass).fields) {
        val fieldType = resolveGraphQLType(field, scalarMap, enumTypeMap)
        builder.field { f ->
            f.name(field.name).type(fieldType).dataFetcher { env ->
                env.getSource<Any>()?.let { serializeValue(field.get(it)) }
            }
        }
    }
    return builder.build()
}

private fun buildModelObjectType(typeName: String, propsType: GraphQLObjectType): GraphQLObjectType {
    val modelTypeName = "${typeName}Model"
    return GraphQLObjectType.newObject().name(modelTypeName)
        .field { it.name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("type").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("state").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
        .field { it.name("createdAt").type(GraphQLNonNull.nonNull(GraphQLTypeReference("Instant"))) }
        .field { it.name("lastModifiedAt").type(GraphQLNonNull.nonNull(GraphQLTypeReference("Instant"))) }
        .field { it.name("lastPropsModifiedAt").type(GraphQLNonNull.nonNull(GraphQLTypeReference("Instant"))) }
        .field { it.name("lastStateTransitionAt").type(GraphQLNonNull.nonNull(GraphQLTypeReference("Instant"))) }
        .field { it.name("props").type(GraphQLNonNull.nonNull(propsType)) }
        .field { it.name("possibleEvents").type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference("KlerkCommand")))) }
        .build()
}

// ---------------------------------------------------------------------------
// Type resolution helpers
// ---------------------------------------------------------------------------

private fun resolveGraphQLType(field: SchemaField, scalarMap: MutableMap<String, GraphQLScalarType>, enumTypeMap: MutableMap<String, GraphQLEnumType>): GraphQLOutputType {
    if (field.isCollection) return Scalars.GraphQLString
    return when (field.type) {
        PropertyType.Int -> Scalars.GraphQLInt
        PropertyType.Float -> Scalars.GraphQLFloat
        PropertyType.Boolean -> Scalars.GraphQLBoolean
        PropertyType.Instant -> getOrCreateScalar("Instant", Scalars.GraphQLString, scalarMap) { it.toString() }
        PropertyType.Duration -> getOrCreateScalar("Duration", Scalars.GraphQLString, scalarMap) { it.toString() }
        PropertyType.Enum -> getOrCreateEnumType(field, enumTypeMap)
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

private fun getOrCreateEnumType(field: SchemaField, enumTypeMap: MutableMap<String, GraphQLEnumType>): GraphQLEnumType {
    // A constant with a body is an instance of a subclass of the enum class.
    val enumClass = field.enumConstants.firstOrNull()?.javaClass?.let { if (it.isEnum) it else it.superclass }
    val enumName = enumClass?.simpleName ?: field.valueClass.simpleName!!
    return enumTypeMap.getOrPut(enumName) {
        val builder = GraphQLEnumType.newEnum().name(enumName)
        field.enumConstants.forEach { builder.value(it.name) }
        builder.build()
    }
}

private fun serializeValue(value: Any?): Any? {
    if (value == null) return null
    return when (value) {
        is StringContainer -> try { value.toString() } catch (e: Exception) { null }
        is IntContainer -> try { value.toString() } catch (e: Exception) { null }
        is LongContainer -> try { value.toString() } catch (e: Exception) { null }
        is FloatContainer -> try { value.toString() } catch (e: Exception) { null }
        is BooleanContainer -> try { value.toString() } catch (e: Exception) { null }
        is InstantContainer -> try { value.value.toString() } catch (e: Exception) { null }
        is DurationContainer -> try { value.value.toString() } catch (e: Exception) { null }
        is GeoPositionContainer -> try { value.value.toString() } catch (e: Exception) { null }
        is EnumContainer<*> -> try { value.value.toString() } catch (e: Exception) { null }
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
    return klerk.specification.getViews().map { (type, collection) ->
        mapOf("id" to collection.id.toString(), "type" to type.simpleName!!)
    }
}

/** The Relay connection arguments, shared by every connection field. */
private fun addConnectionArguments(field: GraphQLFieldDefinition.Builder): GraphQLFieldDefinition.Builder = field
    .argument { it.name("first").type(Scalars.GraphQLInt).description("How many items to take, from `after` onwards.") }
    .argument { it.name("after").type(Scalars.GraphQLString).description("Take the items following this cursor.") }
    .argument {
        it.name("last").type(Scalars.GraphQLInt)
            .description("How many items to take, ending at `before`. Requires `before`.")
    }
    .argument { it.name("before").type(Scalars.GraphQLString).description("Take the items preceding this cursor.") }

/**
 * Turns Relay's `(first, after)` / `(last, before)` into [QueryOptions].
 *
 * `last` without `before` would mean "the end of the collection", which a position cursor cannot name; ask for it with
 * `first` instead.
 */
private fun connectionOptions(env: DataFetchingEnvironment): QueryOptions {
    val first = env.getArgument<Int?>("first")
    val last = env.getArgument<Int?>("last")
    val after = env.getArgument<String?>("after")
    val before = env.getArgument<String?>("before")
    require(first == null || last == null) { "Pass 'first' or 'last', not both" }
    require(after == null || before == null) { "Pass 'after' or 'before', not both" }
    require(first == null || first > 0) { "'first' must be positive" }
    require(last == null || last > 0) { "'last' must be positive" }
    if (last != null || before != null) {
        requireNotNull(before) { "'last' needs 'before'; to read from the start of the collection use 'first'" }
        return QueryOptions(
            maxItems = last ?: DEFAULT_PAGE_SIZE,
            cursor = QueryListCursor.parse(before),
            direction = PageDirection.BEFORE,
            countTotal = true,
        )
    }
    // Relay's `after` is exclusive: the page starts at the item following the one the cursor names.
    return QueryOptions(
        maxItems = first ?: DEFAULT_PAGE_SIZE,
        cursor = after?.let { QueryListCursor.parse(it) },
        direction = if (after == null) PageDirection.FROM else PageDirection.AFTER,
        countTotal = true,
    )
}

private const val DEFAULT_PAGE_SIZE = 10

/** Assembles a Relay connection: one edge per item, each carrying its own cursor. */
private fun <T : Any> connection(result: QueryResponse<T>, nodes: List<Map<String, Any?>>): Map<String, Any?> {
    val edges = nodes.mapIndexed { index, node ->
        mapOf("node" to node, "cursor" to result.cursorAt(index).toString())
    }
    return mapOf(
        "edges" to edges,
        "pageInfo" to mapOf(
            "hasPreviousPage" to result.hasPreviousPage,
            "hasNextPage" to result.hasNextPage,
            // Relay's startCursor/endCursor are the first and last *edge* of this page, not the first and last page.
            "startCursor" to edges.firstOrNull()?.get("cursor"),
            "endCursor" to edges.lastOrNull()?.get("cursor"),
        ),
        "totalCount" to result.totalCount,
    )
}

private suspend fun <C : KlerkContext, V> modelsDataFetcher(
    klerk: Klerk<C, V>,
    contextFactory: suspend (GraphQLContext) -> C,
    env: DataFetchingEnvironment
): Map<String, Any?> {
    val context = contextFactory(env.graphQlContext)
    val viewId = env.getArgument<String>("viewId")!!
    val whereJson = env.getArgument<String?>("where")
    val whereMap: Map<String, Any>? = if (whereJson != null) {
        @Suppress("UNCHECKED_CAST")
        jackson.readValue(whereJson, Map::class.java) as Map<String, Any>
    } else null
    val view = klerk.specification.getView(ViewId.parse(viewId))
    // The filter goes into the query, so `first: 10` really does return ten matching models when there are ten.
    val result = klerk.read(context) {
        view.query(connectionOptions(env)) { whereMap == null || matchesWhere(it.props, whereMap) }
    }
    val nodes = result.items.map { item ->
        genericModelMap(item, klerk.read(context) { getPossibleEvents(item.id) }, klerk)
    }
    return connection(result, nodes)
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
    val managed = klerk.specification.managedModels.single { it.kClass.simpleName == type }
    // Through a read block, so the validation rules get a say -- an event the actor could not actually submit is not
    // offered as a command.
    return klerk.read(context) { getPossibleVoidEvents(managed.kClass) }
        .map { commandToMap(it, klerk.specification.parametersSchema(it)) }
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
): Map<String, Any?> {
    val context = contextFactory(env.graphQlContext)
    val viewId = env.getArgument<String>("viewId")!!
    @Suppress("UNCHECKED_CAST")
    val whereMap = env.getArgument<Map<String, Any>?>("where")
    @Suppress("UNCHECKED_CAST")
    val stateFilter = env.getArgument<Map<String, Any>?>("state")
    @Suppress("UNCHECKED_CAST")
    val createdAtFilter = env.getArgument<Map<String, Any>?>("createdAt")
    val view = klerk.specification.getView(ViewId.parse(viewId))
    // Every filter goes into the query, so a page is full whenever enough models match.
    val result = klerk.read(context) {
        view.query(connectionOptions(env)) { item ->
            (whereMap == null || matchesWhere(item.props, whereMap)) &&
                (stateFilter == null || matchesComparisonExp(item.state, stateFilter)) &&
                (createdAtFilter == null || matchesComparisonExp(item.createdAt.toString(), createdAtFilter))
        }
    }
    val nodes = result.items.map { item ->
        typedModelMap(item, klerk.read(context) { getPossibleEvents(item.id) }, klerk)
    }
    return connection(result, nodes)
}

private fun <C : KlerkContext, V> typedModelMap(
    model: Model<out Any>,
    eventReferences: Set<EventReference>,
    klerk: Klerk<C, V>
): Map<String, Any?> {
    val commands = eventReferences.map { commandToMap(it, klerk.specification.parametersSchema(it)) }
    return mapOf(
        "id" to model.id.toString(),
        "type" to (model.props::class.simpleName ?: ""),
        "state" to model.state,
        "createdAt" to model.createdAt,
        "lastModifiedAt" to model.lastModifiedAt,
        "lastPropsModifiedAt" to model.lastPropsUpdateAt,
        "lastStateTransitionAt" to model.lastStateTransitionAt,
        "props" to model.props,
        "possibleEvents" to commands
    )
}

private fun <C : KlerkContext, V> genericModelMap(
    model: Model<out Any>,
    eventReferences: Set<EventReference>,
    klerk: Klerk<C, V>
): Map<String, Any?> {
    val props = ObjectSchema.of(model.props::class).fields.map { field ->
        mapOf(
            "name" to field.name,
            "type" to field.valueClass.simpleName,
            "value" to serializeValue(field.get(model.props))
        )
    }
    val commands = eventReferences.map { commandToMap(it, klerk.specification.parametersSchema(it)) }
    return mapOf(
        "id" to model.id.toString(),
        "type" to model.props::class.simpleName,
        "state" to model.state,
        "createdAt" to model.createdAt,
        "lastModifiedAt" to model.lastModifiedAt,
        "lastPropsModifiedAt" to model.lastPropsUpdateAt,
        "lastStateTransitionAt" to model.lastStateTransitionAt,
        "props" to props,
        "possibleEvents" to commands
    )
}

/**
 * Builds a per-model WhereInput type with per-field comparison expression input types.
 * Each field gets a `<TypeName><FieldName>ComparisonExp` input type with operators:
 * `_eq`, `_neq`, `_gt`, `_lt`, `_gte`, `_lte`, `_like`, `_ilike`, `_in`, `_is_null`.
 * The WhereInput also supports `_and`, `_or`, `_not` for boolean composition.
 */
internal fun buildWhereInputType(kClass: KClass<*>): GraphQLInputObjectType {
    val typeName = kClass.simpleName!!
    val whereTypeName = "${typeName}WhereInput"
    val builder = GraphQLInputObjectType.newInputObject().name(whereTypeName)

    for (prop in ObjectSchema.of(kClass).fields) {
        val compExpName = "${typeName}${prop.name.replaceFirstChar { it.uppercase() }}ComparisonExp"
        val compExp = GraphQLInputObjectType.newInputObject().name(compExpName)
            .field { it.name("_eq").type(Scalars.GraphQLString) }
            .field { it.name("_neq").type(Scalars.GraphQLString) }
            .field { it.name("_gt").type(Scalars.GraphQLString) }
            .field { it.name("_lt").type(Scalars.GraphQLString) }
            .field { it.name("_gte").type(Scalars.GraphQLString) }
            .field { it.name("_lte").type(Scalars.GraphQLString) }
            .field { it.name("_like").type(Scalars.GraphQLString) }
            .field { it.name("_ilike").type(Scalars.GraphQLString) }
            .field { it.name("_in").type(GraphQLList.list(GraphQLNonNull.nonNull(Scalars.GraphQLString))) }
            .field { it.name("_is_null").type(Scalars.GraphQLBoolean) }
            .build()
        builder.field { it.name(prop.name).type(compExp) }
    }

    // Boolean operators
    builder.field { it.name("_and").type(GraphQLList.list(GraphQLTypeReference(whereTypeName))) }
    builder.field { it.name("_or").type(GraphQLList.list(GraphQLTypeReference(whereTypeName))) }
    builder.field { it.name("_not").type(GraphQLTypeReference(whereTypeName)) }

    return builder.build()
}

/**
 * Evaluates a where map against a props object.
 * The map may contain field names (each mapping to a comparison-exp map) and/or
 * `_and`, `_or`, `_not` boolean operators.
 */
@Suppress("UNCHECKED_CAST")
internal fun matchesWhere(props: Any, where: Map<String, Any?>): Boolean {
    for ((key, value) in where) {
        when (key) {
            "_and" -> {
                val list = value as? List<Map<String, Any?>> ?: continue
                if (!list.all { matchesWhere(props, it) }) return false
            }
            "_or" -> {
                val list = value as? List<Map<String, Any?>> ?: continue
                if (list.isNotEmpty() && !list.any { matchesWhere(props, it) }) return false
            }
            "_not" -> {
                val sub = value as? Map<String, Any?> ?: continue
                if (matchesWhere(props, sub)) return false
            }
            else -> {
                // key is a field name
                val compExp = value as? Map<String, Any?> ?: continue
                val field = ObjectSchema.of(props::class).field(key) ?: return false
                if (!matchesComparisonExp(field.get(props), compExp)) return false
            }
        }
    }
    return true
}

@Suppress("UNCHECKED_CAST")
internal fun matchesComparisonExp(rawValue: Any?, compExp: Map<String, Any?>): Boolean {
    for ((op, opValue) in compExp) {
        when (op) {
            "_is_null" -> {
                val expectNull = opValue as? Boolean ?: continue
                val isNull = rawValue == null
                if (expectNull != isNull) return false
            }
            "_in" -> {
                val list = opValue as? List<*> ?: continue
                val strValue = serializeValue(rawValue)?.toString()
                if (strValue !in list.map { it?.toString() }) return false
            }
            else -> {
                val strValue = serializeValue(rawValue)?.toString() ?: return false
                val cmpValue = opValue?.toString() ?: return false
                val matches = when (op) {
                    "_eq" -> strValue == cmpValue
                    "_neq" -> strValue != cmpValue
                    "_gt" -> strValue > cmpValue
                    "_lt" -> strValue < cmpValue
                    "_gte" -> strValue >= cmpValue
                    "_lte" -> strValue <= cmpValue
                    "_like" -> likeToRegex(cmpValue).matches(strValue)
                    "_ilike" -> likeToRegex(cmpValue, ignoreCase = true).matches(strValue)
                    else -> true
                }
                if (!matches) return false
            }
        }
    }
    return true
}

private fun likeToRegex(pattern: String, ignoreCase: Boolean = false): Regex {
    val regexStr = buildString {
        append("^")
        for (ch in pattern) {
            when (ch) {
                '%' -> append(".*")
                '_' -> append(".")
                else -> append(Regex.escape(ch.toString()))
            }
        }
        append("$")
    }
    return if (ignoreCase) Regex(regexStr, RegexOption.IGNORE_CASE) else Regex(regexStr)
}

private fun commandToMap(
    ref: EventReference,
    parameters: ObjectSchema<*>?
): Map<String, Any?> {
    val params = parameters?.fields?.map { p ->
        mapOf(
            "name" to p.name,
            "type" to (p.type?.name ?: "[?]"),
            "ofType" to p.referencedModel?.qualifiedName,
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

    val eventObj = klerk.specification.getEvent(EventReference.parse(event))
    val parameterInfo = klerk.specification.parametersSchema(eventObj.id)
    val paramsObject = parameterInfo?.fromJson(paramsJson)

    val result = klerk.handle(
        Command.dynamic(
            eventObj,
            if (modelArg == null) null else ModelID(modelArg.toInt()),
            paramsObject
        ),
        context,
        ProcessingOptions(CommandToken.simple(), dryRun = dryRun)
    )

    return when (result) {
        is CommandResult.Success -> mapOf(
            "createdModels" to result.createdModels.map { it.toString() },
            "modifiedModels" to result.updatedModels.map { it.toString() },
            "deletedModels" to result.deletedModels.map { it.toString() },
            "generatedJobs" to result.jobs.map { it.value.toString() }
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
