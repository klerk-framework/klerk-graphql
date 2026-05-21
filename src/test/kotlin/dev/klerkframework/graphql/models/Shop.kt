package dev.klerkframework.graphql.models

import dev.klerkframework.graphql.Context
import dev.klerkframework.graphql.MyViews
import dev.klerkframework.klerk.ArgForVoidEvent
import dev.klerkframework.klerk.EventVisibility.EXTERNAL
import dev.klerkframework.klerk.InstanceEventNoParameters
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.collection.ModelView
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine

data class Shop(
    val bestSellers: List<ModelID<Book>>,
    val faxNumber: FaxNumber?,
)

enum class ShopStates {
    Deletable,
}

fun shopStateMachine(): StateMachine<Shop, ShopStates, Context, MyViews> =
    stateMachine {

        event(CreateShop) {}

        event(DeleteShop) {}

        voidState {
            onEvent(CreateShop) {
                createModel(ShopStates.Deletable, ::newShop)
            }
        }

        state(ShopStates.Deletable) {
            onEvent(DeleteShop) {
                delete()
            }
        }
    }

object CreateShop : VoidEventWithParameters<Shop, CreateShopParams>(Shop::class, EXTERNAL, CreateShopParams::class)

object PublishShop : InstanceEventNoParameters<Shop>(Shop::class, EXTERNAL)

object DeleteShop : InstanceEventNoParameters<Shop>(Shop::class, EXTERNAL)

data class CreateShopParams(
    val faxNumber: FaxNumber?,
)

class FaxNumber(value: String) : StringContainer(value) {
    override val minLength = 3
    override val maxLength = 20
    override val maxLines = 1
}

fun newShop(args: ArgForVoidEvent<Shop, CreateShopParams, Context, MyViews>): Shop {
    val params = args.command.params
    return Shop(faxNumber = params.faxNumber, bestSellers = emptyList())
}
