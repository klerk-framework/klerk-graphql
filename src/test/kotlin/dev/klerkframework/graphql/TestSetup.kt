package dev.klerkframework.graphql

import dev.klerkframework.graphql.AuthorStates.*
import dev.klerkframework.graphql.models.Author
import dev.klerkframework.graphql.models.Book
import dev.klerkframework.graphql.models.CreateAuthor
import dev.klerkframework.graphql.models.CreateAuthorParams
import dev.klerkframework.graphql.models.CreateBook
import dev.klerkframework.graphql.models.CreateBookParams
import dev.klerkframework.graphql.models.DeleteAuthor
import dev.klerkframework.graphql.models.DeleteBook
import dev.klerkframework.graphql.models.FaxNumber
import dev.klerkframework.graphql.models.Shop
import dev.klerkframework.graphql.models.authorStateMachine
import dev.klerkframework.graphql.models.bookStateMachine
import dev.klerkframework.graphql.models.shopStateMachine
import dev.klerkframework.klerk.ActorIdentity
import dev.klerkframework.klerk.ArgCommandContextReader
import dev.klerkframework.klerk.ArgContextReader
import dev.klerkframework.klerk.ArgForInstanceEvent
import dev.klerkframework.klerk.ArgForVoidEvent
import dev.klerkframework.klerk.ArgModelContextReader
import dev.klerkframework.klerk.ArgsForPropertyAuth
import dev.klerkframework.klerk.AuthenticationIdentity
import dev.klerkframework.klerk.Config
import dev.klerkframework.klerk.ConfigBuilder
import dev.klerkframework.klerk.EventVisibility.EXTERNAL
import dev.klerkframework.klerk.Klerk
import dev.klerkframework.klerk.KlerkContext
import dev.klerkframework.klerk.Model
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.ModelIdentity
import dev.klerkframework.klerk.NegativeAuthorization
import dev.klerkframework.klerk.NegativeAuthorization.Deny
import dev.klerkframework.klerk.NegativeAuthorization.Pass
import dev.klerkframework.klerk.PositiveAuthorization
import dev.klerkframework.klerk.PropertyCollectionValidity
import dev.klerkframework.klerk.PropertyCollectionValidity.*
import dev.klerkframework.klerk.SystemIdentity
import dev.klerkframework.klerk.Translation
import dev.klerkframework.klerk.Unauthenticated
import dev.klerkframework.klerk.VoidEventNoParameters
import dev.klerkframework.klerk.collection.AllModelView
import dev.klerkframework.klerk.collection.FilteredModelView
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.collection.ModelViews
import dev.klerkframework.klerk.collection.QueryListCursor
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.*
import dev.klerkframework.klerk.job.JobMetadata
import dev.klerkframework.klerk.job.JobResult
import dev.klerkframework.klerk.job.RunnableJob
import dev.klerkframework.klerk.misc.AlgorithmBuilder
import dev.klerkframework.klerk.misc.Decision
import dev.klerkframework.klerk.misc.FlowChartAlgorithm
import dev.klerkframework.klerk.read.Reader
import dev.klerkframework.klerk.storage.Persistence
import dev.klerkframework.klerk.storage.RamStorage
import dev.klerkframework.klerk.validation.PropertyValidation
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

var onEnterAmateurStateActionCallback: (() -> Unit)? = null
var onEnterImprovingStateActionCallback: (() -> Unit)? = null

fun createConfig(views: MyViews, storage: Persistence = RamStorage()): Config<Context, MyViews> {
    return ConfigBuilder<Context, MyViews>(views).build {
        persistence(storage)
        managedModels {
            model(Book::class, bookStateMachine(views.authors.all, views), views.books)
            model(Author::class, authorStateMachine(views), views.authors)
            model(Shop::class, shopStateMachine(), views.shops)
        }
        authorization {
            readModels {
                positive {
                    rule(::`Everybody can read`)
                }
                negative {
                    rule(::pelleCannotReadOnMornings)
                    rule(::unauthenticatedCannotReadAstrid)
                }
            }

            readProperties {
                positive {
                    rule(::canReadAllProperties)
                }
                negative {
                    rule(::cannotReadAstrid)
                    rule(::cannotReadFax)
                }
            }
            commands {
                positive {
                    rule(::`Everybody can do everything`)
                }
                negative {
                }
            }
            eventLog {
                positive {
                    rule(::`Everybody can read event log`)
                }
                negative {}
            }
        }
        systemContextProvider(::myContextProvider)
    }
}

fun myContextProvider(systemIdentity: SystemIdentity): Context {
    return Context(systemIdentity)
}

fun cannotReadAstrid(args: ArgsForPropertyAuth<Context, MyViews>): NegativeAuthorization {
    return if (args.property is FirstName && args.property.valueWithoutAuthorization == "Astrid") Deny else Pass
}

fun cannotReadFax(args: ArgsForPropertyAuth<Context, MyViews>): NegativeAuthorization {
    return if (args.property is FaxNumber) Deny else Pass
}

fun canReadAllProperties(args: ArgsForPropertyAuth<Context, MyViews>): PositiveAuthorization {
    return PositiveAuthorization.Allow
}

fun unauthenticatedCannotReadAstrid(args: ArgModelContextReader<Context, MyViews>): NegativeAuthorization {
    val props = args.model.props
    return if (props is Author && props.firstName.value == "Astrid" && args.context.actor is Unauthenticated) Deny else Pass
}

fun `Everybody can do everything`(argCommandContextReader: ArgCommandContextReader<*, Context, MyViews>): PositiveAuthorization {
    return PositiveAuthorization.Allow
}


fun `Everybody can read event log`(args: ArgContextReader<Context, MyViews>): PositiveAuthorization {
    return PositiveAuthorization.Allow
}

fun `Everybody can read`(args: ArgModelContextReader<Context, MyViews>): PositiveAuthorization {
    return PositiveAuthorization.Allow
}

fun pelleCannotReadOnMornings(
    args: ArgModelContextReader<Context, MyViews>
): NegativeAuthorization {
    try {
        if (args.context.user?.props?.name?.value.equals("Pelle")) {
            return if (args.context.time.toLocalDateTime(TimeZone.currentSystemDefault()).time < LocalTime.fromSecondOfDay(
                    3600 * 12
                )
            ) Deny else Pass
        }
    } catch (e: Exception) {
        //
    }
    return Pass
}

class BookCollections : ModelViews<Book, Context>() {

    fun childrensBooks(): List<ModelID<Book>> {
        return emptyList()
    }
}

class AuthorCollections<V>(val allBooks: AllModelView<Book, Context>) : ModelViews<Author, Context>() {

    private val greatAuthorNames = setOf("Linus", "Bertil")

    val greatAuthors = this.all.filter { greatAuthorNames.contains(it.props.firstName.value) }.register("greatAuthors")
    val establishedAuthors = this.all.filter { it.state == Established.name }.register("establishedAuthors")
    val establishedGreatAuthors =
        greatAuthors.filter { it.state == Established.name }.register("establishedGreatAuthors")
    lateinit var establishedGreatWithAtLeastTwoBooks: AuthorsWithAtLeastTwoBooks<V>

    val midrangeAuthors = this.all.filter {
        val i = it.props.lastName.value.toIntOrNull() ?: 0
        return@filter i in 15..24
    }

    override fun initialize() {
        establishedGreatWithAtLeastTwoBooks =
            AuthorsWithAtLeastTwoBooks(all, allBooks)
        establishedGreatWithAtLeastTwoBooks.register("medMinst2Böcker")
    }

}

class ReleasePartyPosition(value: GeoPosition) : GeoPositionContainer(value)

//data class Shop(val shopName: ShopName, val owner: Reference<Author>) : CudModel

class ShopName(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}




class MyOtherJob(override val parameters: String) : RunnableJob<Context, MyViews>() {

    override fun getRunFunction() = MyOtherJob::run

    companion object {
        suspend fun run(metadata: JobMetadata, klerk: Klerk<Context, MyViews>) : JobResult {
            println("Job started")
            return JobResult.Success()
        }
    }

}


fun eventsToDeleteAuthorAndBooks(args: ArgForInstanceEvent<Author, Nothing?, Context, MyViews>): List<Command<Any, Any>> {
    args.reader.apply {
        val result: MutableList<Command<Any, Any>> = mutableListOf()
        val books = getRelated(Book::class, requireNotNull(args.model.id))

        @Suppress("UNCHECKED_CAST")
        books.map { Command(event = DeleteBook, model = it.id, null) }
            .forEach { result.add(it as Command<Any, Any>) }

        @Suppress("UNCHECKED_CAST")
        result.add(
            Command(event = DeleteAuthor, model = requireNotNull(args.model.id), null)
                    as Command<Any, Any>
        )

        return result
    }
}

fun newAuthor(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyViews>): Author {
    val params = args.command.params
    return Author(
        firstName = params.firstName,
        lastName = params.lastName,
        address = Address(Street("kjh"))
    )
}

fun newAuthor2(args: ArgForVoidEvent<Author, Nothing?, Context, MyViews>): Author {
    return Author(FirstName("Auto"), LastName("Created"), Address(Street("Somewhere")))
}


fun updateAuthor(args: ArgForInstanceEvent<Author, Author, Context, MyViews>): Author {
    return args.command.params
}


fun onlyAuthenticationIdentityCanCreateDaniel(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyViews>): PropertyCollectionValidity {
    return if (args.command.params.firstName.value == "Daniel" && args.context.actor != AuthenticationIdentity) Invalid() else Valid
}

fun cannotHaveAnAwfulName(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyViews>): PropertyCollectionValidity {
    return if (args.command.params.firstName.value == "Mike" && args.command.params.lastName.value == "Litoris") Invalid() else Valid
}

fun secretTokenShouldBeZeroIfNameStartsWithM(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyViews>): PropertyCollectionValidity {
    return if (args.command.params.firstName.value.startsWith("M") && args.command.params.secretToken.value != 0L) Invalid() else Valid
}

fun preventUnauthenticated(context: Context): PropertyCollectionValidity {
    return if (context.actor == Unauthenticated) Invalid("Must log in") else Valid
}

fun onlyAllowAuthorNameAstridIfThereIsNoRowling(args: ArgForVoidEvent<Author, CreateAuthorParams, Context, MyViews>): PropertyCollectionValidity {
    args.reader.apply {
        if (args.command.params.firstName.value != "Astrid") {
            return Valid
        }
        val rowling = firstOrNull(views.authors.all) { it.props.firstName.value == "Rowling" }
        return if (rowling == null) Valid else Invalid()
    }
}

enum class AuthorStates {
    Amateur,
    Improving,
    Established,
}

data class MyViews(
    val books: BookCollections,
    val authors: AuthorCollections<MyViews>,
    val shops: ModelViews<Shop, Context>
)

suspend fun createAuthorJKRowling(klerk: Klerk<Context, MyViews>): ModelID<Author> {
    val result = klerk.handle(
        Command(
            event = CreateAuthor,
            model = null,
            params = CreateAuthorParams(
                firstName = FirstName("J.K"),
                lastName = LastName("Rowling"),
                phone = PhoneNumber("+46123456"),
                secretToken = SecretPasscode(234234902359245345),
                //       address = Address(Street("Storgatan"))
            ),
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple()),
    )
    return requireNotNull(result.orThrow().primaryModel)
}

suspend fun createAuthorAstrid(klerk: Klerk<Context, MyViews>): ModelID<Author> {
    val result = klerk.handle(
        Command(
            event = CreateAuthor,
            model = null,
            params = createAstridParameters,
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple()),
    )
    @Suppress("UNCHECKED_CAST")
    return result.orThrow().createdModels.single() as ModelID<Author>
}

val createAstridParameters = CreateAuthorParams(
    firstName = FirstName("Astrid"),
    lastName = LastName("Lindgren"),
    phone = PhoneNumber("+4699999"),
    secretToken = SecretPasscode(234123515123434),
)

suspend fun createBookHarryPotter1(klerk: Klerk<Context, MyViews>, author: ModelID<Author>): ModelID<Book> {
    val result = klerk.handle(
        Command(
            event = CreateBook,
            model = null,
            params = CreateBookParams(
                title = BookTitle("Harry Potter and the Philosopher's Stone"),
                author = author,
                coAuthors = emptySet(),
                previousBooksInSameSeries = emptyList(),
                tags = setOf(BookTag("Fiction"), BookTag("Children")),
                averageScore = AverageScore(3.5f)
            ),
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple())
    )
    return requireNotNull(result.orThrow().primaryModel)
}

suspend fun createBookHarryPotter2(
    klerk: Klerk<Context, MyViews>,
    author: ModelID<Author>,
    previousBooksInSameSeries: List<ModelID<Book>>,
    coAuthors: Set<ModelID<Author>>
): ModelID<Book> {
    val result = klerk.handle(
        Command(
            event = CreateBook,
            model = null,
            params = CreateBookParams(
                title = BookTitle("Harry Potter and the Chamber of Secrets"),
                author = author,
                coAuthors = coAuthors,
                previousBooksInSameSeries = previousBooksInSameSeries,
                tags = setOf(BookTag("Fiction"), BookTag("Children")),
                averageScore = AverageScore(0f)
            ),
        ),
        Context.system(),
        ProcessingOptions(CommandToken.simple()),
    )
    return requireNotNull(result.orThrow().primaryModel)
}

class PhoneNumber(value: String) : StringContainer(value) {
    override val minLength = 3
    override val maxLength = 10
    override val maxLines: Int = 1
}

class EvenIntContainer(value: Int) : IntContainer(value) {
    override val min: Int = Int.MIN_VALUE
    override val max: Int = Int.MAX_VALUE

    override val validators: Set<(translator: Translation) -> PropertyValidation> = setOf(::mustBeEven)

    fun mustBeEven(translator: Translation): PropertyValidation {
        if (valueWithoutAuthorization % 2 == 0) {
            return PropertyValidation.Valid
        }
        return PropertyValidation.Invalid("Must be even")
    }

}

class FirstName(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines: Int = 1
}

class LastName(value: String) : StringContainer(value) {
    override val minLength = 1
    override val maxLength = 50
    override val maxLines: Int = 1
}

class BookTitle(value: String) : StringContainer(value) {
    override val minLength = 2
    override val maxLength = 100
    override val maxLines: Int = 1
    override val regexPattern = ".*"
    override val validators = setOf(::`title must be catchy`)

    private fun `title must be catchy`(translator: Translation): PropertyValidation {
        return PropertyValidation.Valid
    }
}

class BookTag(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

class SecretPasscode(value: Long) : LongContainer(value) {
    override val min: Long = Long.MIN_VALUE
    override val max: Long = Long.MAX_VALUE
}

class IsActive(value: Boolean) : BooleanContainer(value)

class Quantity(value: Int) : IntContainer(value) {
    override val min: Int = 0
    override val max: Int = Int.MAX_VALUE
}

class BookWrittenAt(value: Instant) : InstantContainer(value) {

}

class ReadingTime(value: Duration) : DurationContainer(value)

data class Address(val street: Street)

class Street(value: String) : StringContainer(value) {
    override val minLength: Int = 1
    override val maxLength: Int = 100
    override val maxLines: Int = 1
}

fun addStandardTestConfiguration(auth: Boolean = true): ConfigBuilder<Context, MyViews>.() -> Unit = {
    if (auth) {
        authorization {
            readModels {
                positive {
                    rule(::`Everybody can read`)
                }
                negative {
                    rule(::pelleCannotReadOnMornings)
                }
            }
            commands {
                positive {
                    rule(::`Everybody can do everything`)
                }
                negative {
                }
            }
            eventLog {
                positive {
                    rule(::`Everybody can read event log`)
                }
                negative {}
            }
        }
    }
}

sealed class AlwaysFalseDecisions(
    override val name: String,
    override val function: (ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyViews>) -> Boolean
) : Decision<Boolean, ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyViews>> {
    data object Something : AlwaysFalseDecisions("This will always be false", ::alwaysFalse)

}

fun alwaysFalse(args: ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyViews>): Boolean {
    return false
}


object AlwaysFalseAlgorithm :
    FlowChartAlgorithm<ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyViews>, Boolean>("Always false") {

    override fun configure(): AlgorithmBuilder<ArgForInstanceEvent<Author, CreateAuthorParams, Context, MyViews>, Boolean>.() -> Unit =
        {
            start(AlwaysFalseDecisions.Something)
            booleanNode(AlwaysFalseDecisions.Something) {
                on(true, terminateWith = false)
                on(false, terminateWith = false)
            }
        }
}

data class Context(
    override val actor: ActorIdentity,
    override val auditExtra: String? = null,
    override val time: Instant = Clock.System.now(),
    override val translation: Translation = dev.klerkframework.klerk.DefaultTranslation,
    val user: Model<User>? = null,
    val purpose: String = "Pass the butter",
) : KlerkContext {

    companion object {
        fun fromUser(user: Model<User>): Context {
            return Context(ModelIdentity(user), user = user)
        }

        fun unauthenticated(): Context = Context(Unauthenticated)

        fun authenticationIdentity(): Context = Context(AuthenticationIdentity)

        fun system(): Context = Context(SystemIdentity)

    }

}

data class User(val name: FirstName)

object AnEventWithoutParameters : VoidEventNoParameters<Author>(Author::class, EXTERNAL)

class MyJob : RunnableJob<Context, MyViews>() {
    override val parameters: String = ""

    companion object {
        suspend fun run(metadata: JobMetadata, klerk: Klerk<Context, MyViews>) : JobResult {
            return JobResult.Success()
        }
    }

    override fun getRunFunction() = MyJob::run

}


class AverageScore(value: Float) : FloatContainer(value) {
    override val min: Float = 0f
    override val max: Float = Float.MAX_VALUE
}

class AuthorsWithAtLeastTwoBooks<V>(
    private val authors: ModelView<Author, Context>,
    private val books: AllModelView<Book, Context>,
) : ModelView<Author, Context>(authors) {

    override fun filter(filter: ((Model<Author>) -> Boolean)?): ModelView<Author, Context> {
        return if (filter == null) this else FilteredModelView(this, filter)
    }

    override fun <V> withReader(reader: Reader<Context, V>, cursor: QueryListCursor?): Sequence<Model<Author>> {
        return authors.withReader(reader, cursor).filter { author ->
            books.withReader(reader, null).filter { it.props.author == author.id }.take(2).count() == 2
        }
    }

    override fun <V> contains(value: ModelID<*>, reader: Reader<Context, V>): Boolean {
        return withReader(reader, null).any { it.id == value }
    }

}
