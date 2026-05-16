package dev.klerkframework.graphql.models

import dev.klerkframework.graphql.AverageScore
import dev.klerkframework.graphql.BookTag
import dev.klerkframework.graphql.BookTitle
import dev.klerkframework.graphql.BookWrittenAt
import dev.klerkframework.graphql.Context
import dev.klerkframework.graphql.MyCollections
import dev.klerkframework.graphql.Quantity
import dev.klerkframework.graphql.ReadingTime
import dev.klerkframework.graphql.ReleasePartyPosition
import dev.klerkframework.klerk.ArgForInstanceEvent
import dev.klerkframework.klerk.ArgForVoidEvent
import dev.klerkframework.klerk.EventVisibility.EXTERNAL
import dev.klerkframework.klerk.InstanceEventNoParameters
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.datatypes.GeoPosition
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

data class Book(
    val title: BookTitle,
    val author: ModelID<Author>,
    val coAuthors: Set<ModelID<Author>>,
    val previousBooksInSameSeries: List<ModelID<Book>>,
    val tags: Set<BookTag>,
    val salesPerYear: Set<Quantity>,
    val averageScore: AverageScore,
    val writtenAt: BookWrittenAt,
    val readingTime: ReadingTime,
    val publishedAt: BookWrittenAt?,
    val releasePartyPosition: ReleasePartyPosition,
) {
    override fun toString() = title.value
}

enum class BookStates {
    Draft,
    Published,
}

fun bookStateMachine(allAuthors: ModelView<Author, Context>, collections: MyCollections): StateMachine<Book, BookStates, Context, MyCollections> =
    stateMachine {

        event(CreateBook) {
            validReferences(CreateBookParams::author, collections.authors.all)
        }

        event(PublishBook) {}

        event(DeleteBook) {}

        voidState {
            onEvent(CreateBook) {
                createModel(BookStates.Draft, ::newBook)
            }
        }

        state(BookStates.Draft) {
            onEnter {
                //action(`Send email to editors`)
            }

            onEvent(PublishBook) {
                update(::setPublishTime)
                transitionTo(BookStates.Published)
            }

            onEvent(DeleteBook) {
                delete()
            }

        }

        state(BookStates.Published) {

            onEvent(DeleteBook) {
                delete()
            }
        }

    }

object CreateBook : VoidEventWithParameters<Book, CreateBookParams>(Book::class, EXTERNAL, CreateBookParams::class)

object PublishBook : InstanceEventNoParameters<Book>(Book::class, EXTERNAL)

object DeleteBook : InstanceEventNoParameters<Book>(Book::class, EXTERNAL)

data class CreateBookParams(
    val title: BookTitle,
    val author: ModelID<Author>,
    val coAuthors: Set<ModelID<Author>> = emptySet(),
    val previousBooksInSameSeries: List<ModelID<Book>> = emptyList(),
    val tags: Set<BookTag> = emptySet(),
    val averageScore: AverageScore
)

data class AdvancedParams(
    val titles: List<SimpleParamsPart>,
    val averageScore: AverageScore,
)

data class SimpleParamsPart(val title: BookTitle)

fun setPublishTime(args: ArgForInstanceEvent<Book, Nothing?, Context, MyCollections>): Book {
    return args.model.props.copy(publishedAt = BookWrittenAt(args.context.time))
}


fun newBook(args: ArgForVoidEvent<Book, CreateBookParams, Context, MyCollections>): Book {
    val params = args.command.params
    return Book(
        title = params.title,
        author = params.author,
        coAuthors = params.coAuthors,
        previousBooksInSameSeries = params.previousBooksInSameSeries,
        tags = params.tags,
        salesPerYear = setOf(Quantity(43), Quantity(67)),
        averageScore = params.averageScore,
        writtenAt = BookWrittenAt(Instant.fromEpochSeconds(100000)),
        readingTime = ReadingTime(23.hours),
        publishedAt = null,
        releasePartyPosition = ReleasePartyPosition(GeoPosition(latitude = 1.234, longitude = 3.456))
    )
}
