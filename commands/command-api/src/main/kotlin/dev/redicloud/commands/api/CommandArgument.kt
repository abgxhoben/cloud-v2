package dev.redicloud.commands.api

import java.lang.reflect.Parameter
import kotlin.reflect.KClass
import kotlin.reflect.full.superclasses

class CommandArgument(val subCommand: CommandSubBase, parameter: Parameter, val index: Int) {

    val name: String
    val required: Boolean //TODO
    val clazz: KClass<*>
    val parser: CommandArgumentParser<*>?
    val annotatedSuggester: ICommandSuggester
    val suggester: CommandArgumentSuggester
    val suggesterParameter: Array<String>

    init {
        if (parameter.type.kotlin.superclasses.any { it == ICommandActor::class }) {
            name = "_actor"
            required = false
            clazz = parameter.type.kotlin
            parser = null
            annotatedSuggester = EmptySuggester()
            suggesterParameter = arrayOf()
        }else {
            if (!parameter.isAnnotationPresent(CommandParameter::class.java)) {
                name = parameter.name
                required = !parameter.isImplicit //TODO check String? and Int? etc.
                annotatedSuggester = EmptySuggester()
                suggesterParameter = emptyArray()
            } else {
                val annotation = parameter.getAnnotation(CommandParameter::class.java)
                name = annotation.name.ifEmpty { parameter.name }
                required = annotation.required //TODO check String? and Int? etc.
                annotatedSuggester = ICommandSuggester.SUGGESTERS.firstOrNull { it::class == annotation.suggester } ?: EmptySuggester()
                suggesterParameter = annotation.suggesterArguments
            }
            clazz = parameter.type.kotlin
            parser = CommandArgumentParser.PARSERS.filter {
                it.key.qualifiedName!!.replace("?", "") == clazz.qualifiedName!!.replace("?", "")
            }.values.firstOrNull() ?: throw IllegalStateException("No parser found for ${clazz.qualifiedName} in arguments of '${subCommand.command.getName()} ${subCommand.path}'")
        }
        suggester = CommandArgumentSuggester(this)
    }

    fun isActorArgument(): Boolean = name == "_actor"

    fun isThis(input: String, predict: Boolean): Boolean {
        if (!subCommand.isThis(input, false)) return false
        if (isActorArgument()) return false
        if (input.isEmpty()) return false
        var argumentPath = input.lowercase()
        subCommand.getSubPathsWithoutArguments().forEach {
            argumentPath = argumentPath.replace(it, "")
        }
        val argumentSplit = argumentPath.split(" ")
        if (argumentSplit.size <= index) return false
        if (argumentSplit.size == index + 1 && !argumentPath.endsWith(" ")) return true
        return argumentSplit.size == index && argumentPath.endsWith(" ") && predict
    }

    fun parse(input: String): Any? = parser?.parse(input)

    fun getPathFormat(): String = if (required) "<$name>" else "[$name]"

}