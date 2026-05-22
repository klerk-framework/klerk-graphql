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
    for (managed in klerk.config.managedModels) {
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
            .type(GraphQLTypeReference("KlerkModelsResponse"))
            .argument { it.name("collectionId").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
            .argument { it.name("first").type(Scalars.GraphQLInt) }
            .argument { it.name("where").type(Scalars.GraphQLString).description("JSON-encoded where filter, e.g. '{\"firstName\":{\"_eq\":\"Adam\"}}'" ) }
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

    // Per-model typed queries: e.g. author(id) and authors(collectionId)
    for (managed in klerk.config.managedModels) {
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
                .type(GraphQLList.list(GraphQLNonNull.nonNull(GraphQLTypeReference(modelTypeName))))
                .argument { it.name("collectionId").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)) }
                .argument { it.name("first").type(Scalars.GraphQLInt) }
                .argument { it.name("state").type(stringComparisonExpType).description("Filter by model state") }
                .argument { it.name("createdAt").type(stringComparisonExpType).description("Filter by createdAt timestamp") }
                .argument { it.name("where").type(whereInputType).description("Filter on props") }
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
    additionalTypes.addAll(whereInputMap.values)
    additionalTypes.add(klerkCommandType)
    additionalTypes.add(klerkParameterType)
    additionalTypes.add(klerkCollectionType)
    additionalTypes.add(klerkFieldType)
    additionalTypes.add(genericModelType)
    additionalTypes.add(pageInfoType)
    additionalTypes.add(klerkEdgeType)
    additionalTypes.add(klerkModelsResponseType)
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
    for (prop in kClass.memberProperties) {
        val fieldType = resolveGraphQLType(prop.returnType.classifier as? KClass<*>, scalarMap, enumTypeMap) ?: continue
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

private fun resolveGraphQLType(kClass: KClass<*>?, scalarMap: MutableMap<String, GraphQLScalarType>, enumTypeMap: MutableMap<String, GraphQLEnumType> = mutableMapOf()): GraphQLOutputType? {
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
        StringContainer::class.isSuperclassOf(kClass) -> Scalars.GraphQLString
        IntContainer::class.isSuperclassOf(kClass) -> Scalars.GraphQLInt
        LongContainer::class.isSuperclassOf(kClass) -> Scalars.GraphQLString
        FloatContainer::class.isSuperclassOf(kClass) -> Scalars.GraphQLFloat
        BooleanContainer::class.isSuperclassOf(kClass) -> Scalars.GraphQLBoolean
        InstantContainer::class.isSuperclassOf(kClass) -> getOrCreateScalar("Instant", Scalars.GraphQLString, scalarMap) { it.toString() }
        DurationContainer::class.isSuperclassOf(kClass) -> getOrCreateScalar("Duration", Scalars.GraphQLString, scalarMap) { it.toString() }
        EnumContainer::class.isSuperclassOf(kClass) -> getOrCreateEnumType(kClass, enumTypeMap)
        kClass.java.isEnum -> getOrCreateEnumType(kClass, enumTypeMap)
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

private fun getOrCreateEnumType(kClass: KClass<*>, enumTypeMap: MutableMap<String, GraphQLEnumType>): GraphQLEnumType {
    // For EnumContainer subclasses, find the enum type via the supertype type argument
    val enumClass: Class<*> = if (EnumContainer::class.isSuperclassOf(kClass)) {
        val supertype = kClass.supertypes.firstOrNull { it.classifier == EnumContainer::class }
        val enumKClass = supertype?.arguments?.firstOrNull()?.type?.classifier as? KClass<*>
        enumKClass?.java ?: kClass.java
    } else {
        kClass.java
    }
    val enumName = enumClass.simpleName ?: kClass.simpleName!!
    return enumTypeMap.getOrPut(enumName) {
        val builder = GraphQLEnumType.newEnum().name(enumName)
        @Suppress("UNCHECKED_CAST")
        (enumClass.enumConstants ?: emptyArray()).forEach { constant ->
            builder.value((constant as Enum<*>).name)
        }
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
        is InstantContainer -> try { value.instant.toString() } catch (e: Exception) { null }
        is DurationContainer -> try { value.duration.toString() } catch (e: Exception) { null }
        is GeoPositionContainer -> try { value.geoPosition.toString() } catch (e: Exception) { null }
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
    val whereJson = env.getArgument<String?>("where")
    val whereMap: Map<String, Any>? = if (whereJson != null) {
        @Suppress("UNCHECKED_CAST")
        jackson.readValue(whereJson, Map::class.java) as Map<String, Any>
    } else null
    val collection = klerk.config.getCollection(CollectionId.from(collectionId))
    val result = klerk.read(context) { query(collection, QueryOptions(maxItems = first)) }
    val edges = result.items.filter { whereMap == null || matchesWhere(it.props, whereMap) }.map { item ->
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
    @Suppress("UNCHECKED_CAST")
    val whereMap = env.getArgument<Map<String, Any>?>("where")
    @Suppress("UNCHECKED_CAST")
    val stateFilter = env.getArgument<Map<String, Any>?>("state")
    @Suppress("UNCHECKED_CAST")
    val createdAtFilter = env.getArgument<Map<String, Any>?>("createdAt")
    val collection = klerk.config.getCollection(CollectionId.from(collectionId))
    val result = klerk.read(context) { query(collection, QueryOptions(maxItems = first)) }
    return result.items.filter { item ->
        (whereMap == null || matchesWhere(item.props, whereMap)) &&
        (stateFilter == null || matchesComparisonExp(item.state, stateFilter)) &&
        (createdAtFilter == null || matchesComparisonExp(item.createdAt.toString(), createdAtFilter))
    }.map { item ->
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

    for (prop in kClass.memberProperties) {
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
                val prop = props::class.memberProperties.find { it.name == key } ?: return false
                val rawValue = try {
                    (prop as kotlin.reflect.KProperty1<Any, *>).get(props)
                } catch (e: Exception) {
                    return false
                }
                if (!matchesComparisonExp(rawValue, compExp)) return false
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
