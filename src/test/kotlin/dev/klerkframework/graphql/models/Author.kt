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
import dev.klerkframework.graphql.MyJob
import dev.klerkframework.graphql.MyOtherJob
import dev.klerkframework.graphql.MyViews
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
import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.InstanceEventArgs
import dev.klerkframework.klerk.InstanceEventNoParameters
import dev.klerkframework.klerk.InstanceEventWithParameters
import dev.klerkframework.klerk.LifecycleArgs
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.Validatable
import dev.klerkframework.klerk.VoidEventArgs
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.job.DeclaredJob
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine
import dev.klerkframework.klerk.validation.PropertyCollectionValidity
import dev.klerkframework.klerk.validation.PropertyCollectionValidity.Invalid
import dev.klerkframework.klerk.validation.Valid
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class Author(val firstName: FirstName, val lastName: LastName, val address: Address) : Validatable {
    override fun validators(): Set<() -> PropertyCollectionValidity> = setOf(::noAuthorCanBeNamedJamesClavell)

    private fun noAuthorCanBeNamedJamesClavell(): PropertyCollectionValidity =
        if (firstName.value == "James" && lastName.value == "Clavell") Invalid() else Valid

    override fun toString(): String = "$firstName $lastName"
}

fun authorStateMachine(collections: MyViews): StateMachine<Author, AuthorStates, Context, MyViews> = stateMachine {
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
            commands(::eventsToDeleteAuthorAndBooks)
        }

        onEvent(ImproveAuthor) {
            transitionTo(Improving)
        }

        onEvent(ChangeName) {
            update(::changeNameOfAuthor)
            jobs(::notifyBookStores)
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
            transitionWhen {
                on(::isAnImpostor, Amateur)
                on(::hasTalent, Established)
            }
            jobs(::aJob)
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

fun someUpdate(args: LifecycleArgs<Author, Context, MyViews>): Author =
    args.model.props.copy(lastName = LastName("efter"))

fun onExitUpdate(args: LifecycleArgs<Author, Context, MyViews>): Author =
    args.model.props.copy(FirstName("Changed name after exit"))

fun sayHello(args: LifecycleArgs<Author, Context, MyViews>) {
    println("Hello!")
}

fun later(args: LifecycleArgs<Author, Context, MyViews>): Instant = args.context.time.plus(30.seconds)

fun hasTalent(args: LifecycleArgs<Author, Context, MyViews>): Boolean = true
fun isAnImpostor(args: LifecycleArgs<Author, Context, MyViews>): Boolean = false

fun aJob(args: LifecycleArgs<Author, Context, MyViews>): List<DeclaredJob<Context, MyViews>> = listOf(MyJob.declare(""))

fun onEnterImprovingStateAction(args: LifecycleArgs<Author, Context, MyViews>) {
    if (onEnterImprovingStateActionCallback != null) {
        onEnterImprovingStateActionCallback!!()
    }
}

fun showNotification(args: InstanceEventArgs<Author, Nothing?, Context, MyViews>) {
    println("It was decided that we should show a notification")
}

fun onEnterAmateurStateAction(args: LifecycleArgs<Author, Context, MyViews>) {
    if (onEnterAmateurStateActionCallback != null) {
        onEnterAmateurStateActionCallback!!()
    }
}

fun notifyBookStores(
    args: InstanceEventArgs<Author, ChangeNameParams, Context, MyViews>,
): List<DeclaredJob<Context, MyViews>> = listOf(MyOtherJob.declare(""))

data class CreateAuthorParams(
    val firstName: FirstName,
    val lastName: LastName,
    val phone: PhoneNumber,
    val age: EvenIntContainer = EvenIntContainer(68),
    //  val address: Address,
    val secretToken: SecretPasscode,
    val favouriteColleague: ModelID<Author>? = null,
) : Validatable {

    override fun validators(): Set<() -> PropertyCollectionValidity> =

        setOf(::augustStrindbergCannotHaveCertainPhoneNumber)

    private fun augustStrindbergCannotHaveCertainPhoneNumber(): PropertyCollectionValidity {
        val isAugust = firstName.value == "August" && lastName.value == "Strindberg"
        return if (isAugust && phone.value == "123456") Invalid() else Valid
    }
}

data class ChangeNameParams(val updatedFirstName: FirstName, val updatedLastName: LastName)

object CreateAuthor :
    VoidEventWithParameters<Author, CreateAuthorParams>(External)

object UpdateAuthor : InstanceEventWithParameters<Author, Author>(External)

object DeleteAuthor : InstanceEventNoParameters<Author>(External)

object DeleteAuthorAndBooks : InstanceEventNoParameters<Author>(External)

object ImproveAuthor : InstanceEventNoParameters<Author>(External)

object ChangeName : InstanceEventWithParameters<Author, ChangeNameParams>(External)

fun changeNameOfAuthor(args: InstanceEventArgs<Author, ChangeNameParams, Context, MyViews>): Author =
    args.model.props.copy(
        firstName = args.command.params.updatedFirstName,
        lastName = args.command.params.updatedLastName,
    )

object CreateAuthorTheAdvancedWay : VoidEventWithParameters<Author, AdvancedParams>(External)

fun newAuthorFromAdvancedParams(args: VoidEventArgs<Author, AdvancedParams, Context, MyViews>): Author {
    val params = args.command.params
    println("Doing something with ${params.titles.joinToString { it.title.value }} and ${params.averageScore.value}")
    return Author(
        firstName = FirstName("Advanced"),
        lastName = LastName("Author"),
        address = Address(Street("kjh")),
    )
}
