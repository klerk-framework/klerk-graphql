package dev.klerkframework.graphql

import dev.klerkframework.klerk.misc.ObjectSchema
import graphql.Scalars
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLTypeReference
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlin.reflect.KClass

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
        val compExpName = "$typeName${prop.name.replaceFirstChar { it.uppercase() }}ComparisonExp"
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
