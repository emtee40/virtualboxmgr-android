package com.kedzie.vbox.processor

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Type
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.TypeParameter
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.TypeParameter.Variance
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.deserialization.NameResolver

internal fun TypeParameter.asTypeName(
        nameResolver: NameResolver,
        getTypeParameter: (index: Int) -> TypeParameter,
        resolveAliases: Boolean = false
): TypeVariableName {
    val possibleBounds = upperBoundList.map {
        it.asTypeName(nameResolver, getTypeParameter, resolveAliases)
    }
    return if (possibleBounds.isEmpty()) {
        TypeVariableName(
                name = nameResolver.getString(name),
                variance = variance.asKModifier())
    } else {
        TypeVariableName(
                name = nameResolver.getString(name),
                bounds = *possibleBounds.toTypedArray(),
                variance = variance.asKModifier())
    }
}

internal fun TypeParameter.Variance.asKModifier(): KModifier? {
    return when (this) {
        Variance.IN -> KModifier.IN
        Variance.OUT -> KModifier.OUT
        Variance.INV -> null
    }
}

/**
 * Returns the TypeName of this type as it would be seen in the source code, including nullability
 * and generic type parameters.
 *
 * @param [nameResolver] a [NameResolver] instance from the source proto
 * @param [getTypeParameter] a function that returns the type parameter for the given index. **Only
 *     called if [ProtoBuf.Type.hasTypeParameter] is true!**
 */
internal fun Type.asTypeName(
        nameResolver: NameResolver,
        getTypeParameter: (index: Int) -> TypeParameter,
        useAbbreviatedType: Boolean = true
): TypeName {

    val argumentList = when {
        useAbbreviatedType && hasAbbreviatedType() -> abbreviatedType.argumentList
        else -> argumentList
    }

    if (hasFlexibleUpperBound()) {
        return WildcardTypeName.producerOf(
                flexibleUpperBound.asTypeName(nameResolver, getTypeParameter, useAbbreviatedType))
                .copy(nullable = nullable)
    } else if (hasOuterType()) {
        return WildcardTypeName.consumerOf(
                outerType.asTypeName(nameResolver, getTypeParameter, useAbbreviatedType))
                .copy(nullable = nullable)
    }

    val realType = when {
        hasTypeParameter() -> return getTypeParameter(typeParameter)
                .asTypeName(nameResolver, getTypeParameter, useAbbreviatedType)
                .copy(nullable = nullable)
        hasTypeParameterName() -> typeParameterName
        useAbbreviatedType && hasAbbreviatedType() -> abbreviatedType.typeAliasName
        else -> className
    }

    var typeName: TypeName =
            ClassName.bestGuess(nameResolver.getString(realType)
                    .replace("/", "."))

    if (argumentList.isNotEmpty()) {
        val remappedArgs: Array<TypeName> = argumentList.map { argumentType ->
            val nullableProjection = if (argumentType.hasProjection()) {
                argumentType.projection
            } else null
            if (argumentType.hasType()) {
                argumentType.type.asTypeName(nameResolver, getTypeParameter, useAbbreviatedType)
                        .let { argumentTypeName ->
                            nullableProjection?.let { projection ->
                                when (projection) {
                                    Type.Argument.Projection.IN -> WildcardTypeName.consumerOf(argumentTypeName)
                                    Type.Argument.Projection.OUT -> WildcardTypeName.producerOf(argumentTypeName)
                                    Type.Argument.Projection.STAR -> STAR
                                    Type.Argument.Projection.INV -> TODO("INV projection is unsupported")
                                }
                            } ?: argumentTypeName
                        }
            } else {
                STAR
            }
        }.toTypedArray()
        typeName = (typeName as ClassName).parameterizedBy(*remappedArgs)
    }

    return typeName.copy(nullable = nullable)
}
