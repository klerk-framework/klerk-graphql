package dev.klerkframework.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.hooks.SchemaGeneratorHooks
import com.expediagroup.graphql.generator.scalars.ID
import graphql.Scalars
import graphql.schema.GraphQLScalarType
import com.expediagroup.graphql.server.operations.Query
import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.collection.QueryListCursor
import dev.klerkframework.klerk.collection.QueryOptions
import graphql.GraphQLContext
import graphql.GraphqlErrorException
import graphql.schema.DataFetchingEnvironment
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.full.createType

/**
 * Base class for typed Klerk model wrappers. Subclass this for each model type to get a concrete
 * GraphQL type with a typed [props] field.
 *
 * Example:
 * ```kotlin
 * class AuthorModel(
 *     id: ID, type: String, state: String, createdAt: String,
 *     lastModifiedAt: String, lastPropsModifiedAt: String, lastStateTransitionAt: String,
 *     props: Author, possibleEvents: List<KlerkCommand>
 * ) : TypedKlerkModel<Author>(id, type, state, createdAt, lastModifiedAt, lastPropsModifiedAt,
 *     lastStateTransitionAt, props, possibleEvents)
 * ```
 */
@GraphQLIgnore
public abstract class TypedKlerkModel<T>(
    public val id: ID,
    public val type: String,
    public val state: String,
    public val createdAt: String,
    public val lastModifiedAt: String,
    public val lastPropsModifiedAt: String,
    public val lastStateTransitionAt: String,
    public val props: T,
    public val possibleEvents: List<KlerkCommand>
)

/**
 * Base class for typed Klerk query services. Subclass this for each model type, providing
 * a concrete [TypedKlerkModel] subclass [W] as the return type.
 *
 * The base [model] and [models] methods are `protected` and must be exposed by subclasses under
 * unique names to avoid GraphQL field name conflicts when multiple query services are registered.
 *
 * Example:
 * ```kotlin
 * class AuthorModel(...) : TypedKlerkModel<Author>(...)
 *
 * class AuthorQuery(klerk: Klerk<Context, MyCollections>, contextFactory: suspend (GraphQLContext) -> Context)
 *     : TypedKlerkQueryService<Context, MyCollections, Author, AuthorModel>(klerk, contextFactory, ::AuthorModel) {
 *     suspend fun author(id: ID, env: DataFetchingEnvironment): AuthorModel? = model(id, env)
 *     suspend fun authors(collectionId: String, first: Int? = 10, after: String? = null, before: String? = null, env: DataFetchingEnvironment): List<AuthorModel> = models(collectionId, first, after, before, env)
 * }
 * ```
 */
public abstract class TypedKlerkQueryService<C : KlerkContext, V, T : Any, W : TypedKlerkModel<T>>(
    private val klerk: Klerk<C, V>,
    private val contextFactory: suspend (GraphQLContext) -> C,
    private val factory: (ID, String, String, String, String, String, String, T, List<KlerkCommand>) -> W
) : Query {

    protected suspend fun model(id: ID, env: DataFetchingEnvironment): W? {
        try {
            val context = contextFactory(env.graphQlContext)
            val klerkModel = klerk.read(context) { getOrNull(ModelID(id.value.toInt())) } ?: return null
            val events = klerk.read(context) { getPossibleEvents(klerkModel.id) }
            return buildModel(klerkModel, events)
        } catch (e: Exception) {
            if (e is AuthorizationException) {
                throw GraphqlErrorException.newErrorException().message("The operation is not authorized").build()
            }
            throw GraphqlErrorException.newErrorException().build()
        }
    }

    protected suspend fun models(
        collectionId: String,
        first: Int? = 10,
        after: String? = null,
        before: String? = null,
        env: DataFetchingEnvironment
    ): List<W> {
        try {
            val context = contextFactory(env.graphQlContext)
            require(!(after != null && before != null))
            var cursor = after?.let { QueryListCursor.fromString(after) }
            if (before != null) {
                cursor = QueryListCursor.fromString(before)
            }
            val collection = klerk.config.getCollection(CollectionId.from(collectionId))
            val result = klerk.read(context) {
                query(collection, QueryOptions(maxItems = first ?: 10, cursor))
            }
            return result.items.map { item ->
                val possibleEvents = klerk.read(context) { getPossibleEvents(item.id) }
                buildModel(item, possibleEvents)
            }
        } catch (e: Exception) {
            val message = when (e) {
                is AuthorizationException -> "The operation is not authorized"
                is NoSuchElementException -> "Collection not found"
                else -> "Unknown problem"
            }
            throw GraphqlErrorException.newErrorException().message(message).build()
        }
    }

    @GraphQLIgnore
    private fun buildModel(model: Model<out Any>, eventReferences: Set<EventReference>): W {
        @Suppress("UNCHECKED_CAST")
        val props = model.props as T
        val commands = eventReferences.map { KlerkCommand.from(it, klerk.config.getParameters(it)) }
        return factory(
            ID(model.id.toString()),
            model.props::class.simpleName ?: "",
            model.state,
            model.createdAt.toString(),
            model.lastModifiedAt.toString(),
            model.lastPropsUpdateAt.toString(),
            model.lastStateTransitionAt.toString(),
            props,
            commands
        )
    }
}



/**
 * SchemaGeneratorHooks that filters out types from the `dev.klerkframework.klerk` package
 * (such as `Validatable`) which are not valid GraphQL types and should not be included in the schema.
 */
public class KlerkSchemaGeneratorHooks : SchemaGeneratorHooks {
    override fun isValidSuperclass(kClass: KClass<*>): Boolean {
        val pkg = kClass.qualifiedName ?: ""
        if (pkg.startsWith("dev.klerkframework.klerk") && !pkg.startsWith("dev.klerkframework.graphql")) {
            return false
        }
        return super.isValidSuperclass(kClass)
    }

    override fun isValidFunction(kClass: KClass<*>, function: KFunction<*>): Boolean {
        val hasKlerkParam = function.parameters.any { param ->
            param.type.let { containsKlerkType(it) }
        }
        if (hasKlerkParam) return false
        if (containsKlerkType(function.returnType)) return false
        return super.isValidFunction(kClass, function)
    }

    override fun isValidProperty(kClass: KClass<*>, property: KProperty<*>): Boolean {
        if (containsKlerkType(property.returnType)) {
            return false
        }
        return super.isValidProperty(kClass, property)
    }

    private fun containsKlerkType(type: KType): Boolean {
        val classifier = type.classifier as? KClass<*>
        if (classifier != null) {
            val pkg = classifier.qualifiedName ?: ""
            if (pkg.startsWith("dev.klerkframework.klerk") && !pkg.startsWith("dev.klerkframework.graphql")) {
                return true
            }
        }
        return type.arguments.any { it.type?.let { t -> containsKlerkType(t) } == true }
    }

    override fun willGenerateGraphQLType(type: KType): GraphQLScalarType? = when (type.classifier) {
        kotlin.time.Instant::class -> GraphQLScalarType.newScalar(Scalars.GraphQLString).name("Instant").description("A kotlinx Instant value serialized as a String").build()
        kotlin.time.Duration::class -> GraphQLScalarType.newScalar(Scalars.GraphQLString).name("Duration").description("A kotlinx Duration value serialized as a String").build()
        else -> null
    }

    override fun willResolveMonad(type: KType): KType = when (type.classifier) {
        Set::class -> List::class.createType(type.arguments)
        else -> super.willResolveMonad(type)
    }
}
