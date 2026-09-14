package dev.klerkframework.graphql.models

import dev.klerkframework.graphql.Context
import dev.klerkframework.graphql.MyViews
import dev.klerkframework.klerk.VoidEventArgs
import dev.klerkframework.klerk.EventVisibility.External
import dev.klerkframework.klerk.InstanceEventNoParameters
import dev.klerkframework.klerk.ModelID
import dev.klerkframework.klerk.VoidEventWithParameters
import dev.klerkframework.klerk.datatypes.EnumContainer
import dev.klerkframework.klerk.datatypes.StringContainer
import dev.klerkframework.klerk.statemachine.StateMachine
import dev.klerkframework.klerk.statemachine.stateMachine

data class Shop(
    val bestSellers: List<ModelID<Book>>,
    val faxNumber: FaxNumber?,
    val shopSize: ShopSize,
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

object CreateShop : VoidEventWithParameters<Shop, CreateShopParams>(External)

object PublishShop : InstanceEventNoParameters<Shop>(External)

object DeleteShop : InstanceEventNoParameters<Shop>(External)

data class CreateShopParams(
    val faxNumber: FaxNumber?,
)

class FaxNumber(value: String) : StringContainer(value) {
    override val minLength = 3
    override val maxLength = 20
    override val maxLines = 1
}

enum class ShopSizeEnum {
    Small,
    Medium,
    Large,
}

class ShopSize(value: ShopSizeEnum) : EnumContainer<ShopSizeEnum>(value) {
}

fun newShop(args: VoidEventArgs<Shop, CreateShopParams, Context, MyViews>): Shop {
    val params = args.command.params
    return Shop(faxNumber = params.faxNumber, bestSellers = emptyList(), shopSize = ShopSize(ShopSizeEnum.Small))
}
