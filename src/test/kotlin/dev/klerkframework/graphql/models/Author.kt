package dev.klerkframework.graphql.models

import dev.klerkframework.graphql.Address
import dev.klerkframework.graphql.AnEventWithoutParameters
import dev.klerkframework.graphql.AuthorStates
import dev.klerkframework.graphql.AuthorStates.Amateur
import dev.klerkframework.graphql.AuthorStates.Established
import dev.klerkframework.graphql.AuthorStates.Improving
import dev.klerkframework.graphql.Context
import dev.klerkframework.graphql.EvenIntContainer
import dev.klerkframework.graphql.FirstName
import dev.klerkframework.graphql.LastName
import dev.klerkframework.graphql.MyViews
import dev.klerkframework.graphql.MyJob
import dev.klerkframework.graphql.MyOtherJob
import dev.klerkframework.graphql.PhoneNumber
import dev.klerkframework.graphql.SecretPasscode
import dev.klerkframework.graphql.Street
import dev.klerkframework.graphql.cannotHaveAnAwfulName
import dev.klerkframework.graphql.eventsToDeleteAuthorAndBooks
import dev.klerkframework.graphql.newAuthor
import dev.klerkframework.graphql.newAuthor2
import dev.klerkframework.graphql.onEnterAmateurStateActionCallback
import dev.klerkframework.graphql.onEnterImprovingStateActionCallback
import dev.klerkframework.graphql.onlyAuthenticationIdentityCanCreateDaniel
import dev.klerkframework.graphql.secretTokenShouldBeZeroIfNameStartsWithM
import dev.klerkframework.graphql.updateAuthor
import dev.klerkframework.klerk.ArgForInstanceEvent
import dev.klerkframework.klerk.ArgForInstanceNonEvent
import dev.klerkframework.klerk.ArgForVoidEvent
import dev.klerkframework.klerk.EventVisibility.EXTERNAL
import dev.klerkframework.klerk.InstanceEventNoParameters
import dev.klerkframework.klerk.InstanceEventWithParameters
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.PropertyCollectionValidity
import dev.klerkframework.klerk.PropertyCollectionValidity.Invalid
import dev.klerkframework.klerk.PropertyCollectionValidity.Valid
import dev.klerkframework.klerk.Validatable
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.job.RunnableJob
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class Author(val firstName: FirstName, val lastName: LastName, val address: Address) : Validatable {
    override fun validators(): Set<() -> PropertyCollectionValidity> = setOf(::noAuthorCanBeNamedJamesClavell)

    private fun noAuthorCanBeNamedJamesClavell(): PropertyCollectionValidity {
        return if (firstName.value == "James" && lastName.value == "Clavell") Invalid() else Valid
    }

    override fun toString(): String = "$firstName $lastName"
}

fun authorStateMachine(collections: MyViews): StateMachine<Author, AuthorStates, Context, MyViews> =

    stateMachine {

        event(CreateAuthor) {
            //  validateContext(::preventUnauthenticated)
            validateWithParameters(::cannotHaveAnAwfulName)
            validateWithParameters(::secretTokenShouldBeZeroIfNameStartsWithM)
            validateWithParameters(::onlyAuthenticationIdentityCanCreateDaniel)
            validReferences(CreateAuthorParams::favouriteColleague, collections.authors.all)
        }

        event(CreateAuthorTheAdvancedWay) {}

        event(AnEventWithoutParameters) {}

        event(UpdateAuthor) {}

        event(ImproveAuthor) {}

        event(ChangeName) {}

        event(DeleteAuthor) {}

        event(DeleteAuthorAndBooks) {}


        voidState {
            onEvent(CreateAuthor) {
                createModel(Amateur, ::newAuthor)
            }

            onEvent(AnEventWithoutParameters) {
                createModel(Amateur, ::newAuthor2)
            }

            onEvent(CreateAuthorTheAdvancedWay) {
                createModel(Amateur, ::newAuthorFromAdvancedParams)
            }

        }

        state(Amateur) {
            onEnter {
                unmanagedJob(::onEnterAmateurStateAction)
            }

            onEvent(UpdateAuthor) {
                update(::updateAuthor)
            }

            onEvent(DeleteAuthor) {
                delete()
            }

            onEvent(DeleteAuthorAndBooks) {
                createCommands(::eventsToDeleteAuthorAndBooks)
            }

            onEvent(ImproveAuthor) {
                transitionTo(Improving)
            }

            onEvent(ChangeName) {
                update(::changeNameOfAuthor)
                job(::notifyBookStores)
            }

            after(30.seconds) {
                transitionTo(Established)
                update(::someUpdate)
                unmanagedJob(::sayHello)
            }

        }

        state(Improving) {
            onEnter {
                unmanagedJob(::onEnterImprovingStateAction)
                transitionWhen(
                    linkedMapOf(
                        ::isAnImpostor to Amateur,
                        ::hasTalent to Established,
                    )
                )
                job(::aJob)
            }

        }

        state(Established) {

            atTime(::later) {
                delete()
            }

            onEvent(ImproveAuthor) {
            }

            onEvent(DeleteAuthor) {
                delete()
            }
        }

    }

fun someUpdate(args: ArgForInstanceNonEvent<Author, Context, MyViews>): Author {
    return args.model.props.copy(lastName = LastName("efter"))
}

fun onExitUpdate(args: ArgForInstanceNonEvent<Author, Context, MyViews>): Author {
    return args.model.props.copy(FirstName("Changed name after exit"))
}

fun sayHello(args: ArgForInstanceNonEvent<Author, Context, MyViews>) {
    println("Hello!")
}

fun later(args: ArgForInstanceNonEvent<Author, Context, MyViews>): Instant {
    return args.time.plus(30.seconds)
}

fun hasTalent(args: ArgForInstanceNonEvent<Author, Context, MyViews>): Boolean = true
fun isAnImpostor(args: ArgForInstanceNonEvent<Author, Context, MyViews>): Boolean = false

fun aJob(args: ArgForInstanceNonEvent<Author, Context, MyViews>): List<RunnableJob<Context, MyViews>> {
    return listOf(MyJob())
}


fun onEnterImprovingStateAction(args: ArgForInstanceNonEvent<Author, Context, MyViews>) {
    if (onEnterImprovingStateActionCallback != null) {
        onEnterImprovingStateActionCallback!!()
    }
}


fun showNotification(args: ArgForInstanceEvent<Author, Nothing?, Context, MyViews>) {
    println("It was decided that we should show a notification")
}

fun onEnterAmateurStateAction(args: ArgForInstanceNonEvent<Author, Context, MyViews>) {
    if (onEnterAmateurStateActionCallback != null) {
        onEnterAmateurStateActionCallback!!()
    }
}


fun notifyBookStores(args: ArgForInstanceEvent<Author, ChangeNameParams, Context, MyViews>): List<RunnableJob<Context, MyViews>> {
    return listOf(MyOtherJob(""))
}

data class CreateAuthorParams(
    val firstName: FirstName,
    val lastName: LastName,
    val phone: PhoneNumber,
    val age: EvenIntContainer = EvenIntContainer(68),
    //  val address: Address,
    val secretToken: SecretPasscode,
    val favouriteColleague: ModelID<Author>? = null
) : Validatable {

    override fun validators(): Set<() -> PropertyCollectionValidity> = setOf(::augustStrindbergCannotHaveCertainPhoneNumber)

    private fun augustStrindbergCannotHaveCertainPhoneNumber(): PropertyCollectionValidity {
        return if (firstName.value == "August" && lastName.value == "Strindberg" && phone.value == "123456") Invalid() else Valid
    }
}

data class ChangeNameParams(val updatedFirstName: FirstName, val updatedLastName: LastName)



object CreateAuthor :
    VoidEventWithParameters<Author, CreateAuthorParams>(Author::class, EXTERNAL, CreateAuthorParams::class)

object UpdateAuthor : InstanceEventWithParameters<Author, Author>(Author::class, EXTERNAL, Author::class) {

}

object DeleteAuthor : InstanceEventNoParameters<Author>(Author::class, EXTERNAL)

object DeleteAuthorAndBooks : InstanceEventNoParameters<Author>(Author::class, EXTERNAL)

object ImproveAuthor : InstanceEventNoParameters<Author>(Author::class, EXTERNAL)

object ChangeName : InstanceEventWithParameters<Author, ChangeNameParams>(Author::class, EXTERNAL, ChangeNameParams::class)

fun changeNameOfAuthor(args: ArgForInstanceEvent<Author, ChangeNameParams, Context, MyViews>): Author {
    return args.model.props.copy(
        firstName = args.command.params.updatedFirstName,
        lastName = args.command.params.updatedLastName
    )
}

object CreateAuthorTheAdvancedWay : VoidEventWithParameters<Author, AdvancedParams>(Author::class, EXTERNAL, AdvancedParams::class)

fun newAuthorFromAdvancedParams(args: ArgForVoidEvent<Author, AdvancedParams, Context, MyViews>): Author {
    println("Doing something with ${args.command.params.titles.joinToString { it.title.value }} and ${args.command.params.averageScore.value}")
    return Author(
        firstName = FirstName("Advanced"),
        lastName = LastName("Author"),
        address = Address(Street("kjh"))
    )
}
