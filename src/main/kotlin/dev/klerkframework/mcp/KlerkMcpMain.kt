package dev.klerkframework.mcp

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.CommandResult.Failure
import dev.klerkframework.klerk.CommandResult.Success
import dev.klerkframework.klerk.view.asSequence
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.DataContainer
import dev.klerkframework.klerk.misc.ObjectSchema
import dev.klerkframework.klerk.misc.PropertyType
import dev.klerkframework.klerk.statemachine.StateMachine
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Provides the context for executing a command, typically based on the current request or user session. The command
 * is null if no specific command is associated with the request.
 */
public typealias ContextProvider<C> = suspend (command: Command<*, *>?) -> C

//fun configureMcpServer(): Routing.() -> Unit = {
//    mcp {
//        getMcpServer()
//    }
//}

private val logger = LoggerFactory.getLogger("dev.klerkframework.mcp.KlerkMcpMain")

/**
 * The name of the JSON parameter that contains the model ID. All InstanceEvent:s require this parameter.
 */
private const val MODEL_ID_JSON_PARAMETER = "modelID"

/**
 * Creates an MCP server named [mcpServerName] with a tool for every event of every managed model. Commands run in the
 * context [contextProvider] provides.
 */
public fun <C : KlerkContext, V> createMcpServer(
    klerk: Klerk<C, V>,
    contextProvider: ContextProvider<C>,
    mcpServerName: String,
    mcpServerVersion: String,
): Server {
    logger.info("Creating MCP server")

    val server = Server(
        serverInfo = Implementation(
            name = mcpServerName,
            version = mcpServerVersion,
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                tools = ServerCapabilities.Tools(listChanged = null),
            ),
        ),
    )

    for (model in klerk.specification.managedModels) {
        val stateMachine = model.stateMachine

        for (eventReference in stateMachine.eventReferences) {
            logger.debug("Adding tool for model {} and event: {}",model.kClass.simpleName, eventReference.eventName)

            val event = klerk.specification.event(eventReference)

            val required: MutableList<String> = mutableListOf()
            val properties: MutableMap<String, JsonElement> = mutableMapOf()

            if (event is InstanceEvent<*, *>) {
                required.add(MODEL_ID_JSON_PARAMETER)
                properties[MODEL_ID_JSON_PARAMETER] = JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to
                            JsonPrimitive(
                                "Model ID (as base-36 encoded string) of the instance to execute the command on",
                            ),
                    ),
                )
            }

            klerk.specification.parametersSchema(eventReference)?.let { parameters ->
                required.addAll(parameters.fields.filter { it.isRequired }.map { it.name })
                for (eventParameter in parameters.fields) {
                    properties[eventParameter.name] = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive(propertyTypeToJsonType(eventParameter.type)),
                            "description" to JsonPrimitive("Value for the ${eventParameter.valueClass.simpleName}"),
                        ),
                    )
                }
            }

            logger.debug("Tool input properties for ${eventReference.eventName}: {}", properties)
            val inputSchema = ToolSchema(properties = JsonObject(properties), required = required)
            server.addTool(
                name = toToolName(eventReference.eventName, model.kClass.simpleName!!),
                description = "Executes the ${eventReference.eventName} command on the data ${model.kClass.simpleName}",
                inputSchema = inputSchema,
            ) { request ->
                val event = klerk.specification.event(eventReference)
                handleToolRequest(stateMachine, klerk, event, contextProvider, request)
            }
       }

        // Add MCP resources for listing a model. The support for MCP resources in MCP clients are current limited.
        server.addResource(
            uri = "${model.kClass.simpleName}s://all",
            name = "List all ${model.kClass.simpleName}s",
            description = "A list of ${model.kClass.simpleName}s in JSON format",
            mimeType = "application/json",
        ) { request ->
            val models = klerk.read(contextProvider(null)) {
                model.views.all.asSequence().toList()
            }

            val jsonArray = buildJsonArray {
                models.map { modelToJson(it) }.forEach { add(it) }
            }

            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(jsonArray.toString(), request.uri, "application/json"),
                ),
            )
        }

        // Because MCP clients support for resources are limited, it more compatible to add a tool for listing models.
        server.addTool(
            name = "${toSnakeCase(model.kClass.simpleName!!)}_list",
            description = "Lists all ${model.kClass.simpleName!!} models",
        ) { request ->
            val models = klerk.read(contextProvider(null)) {
                model.views.all.asSequence().toList()
            }

            val jsonArray = buildJsonArray {
                models.map { modelToJson(it) }.forEach { add(it) }
            }
            CallToolResult(content = listOf(TextContent(jsonArray.toString())))
        }
    }

    return server
}

internal fun propertyTypeToJsonType(propertyType: PropertyType?): String {
    return when (propertyType) {
        PropertyType.String ->  "string"
        PropertyType.Int, PropertyType.Long, PropertyType.Short, PropertyType.Byte,
        PropertyType.UInt, PropertyType.ULong, PropertyType.UShort, PropertyType.UByte,
        PropertyType.Float, PropertyType.Double -> "number"
        PropertyType.Boolean -> "boolean"
        PropertyType.Ref ->     "string"
        PropertyType.AttachedDataRef -> "string"
        PropertyType.Instant -> "string"
        PropertyType.Date -> "string"
        PropertyType.Duration -> "string"
        PropertyType.Geo -> "string"
        PropertyType.Enum -> "string"
        null -> throw IllegalArgumentException("PropertyType was null!?")
    }
}

internal fun toSnakeCase(camelCase: String): String {
    return camelCase.replace(Regex("([a-z])([A-Z])"), "$1_$2")
        .replace(Regex("([A-Z])([A-Z][a-z])"), "$1_$2")
        .lowercase()
}

internal fun toToolName(eventName: String, modelName: String): String {
    return "${toSnakeCase(modelName)}_${toSnakeCase(eventName)}"
}

/**
 * Builds the parameters of a command for [event] from the arguments of the MCP client's [request], or null if the
 * event has no parameters.
 */
private fun createCommandParams(event: Event<Any, Any?>, request: CallToolRequest): Any? {
    val parametersClass = when(event) {
        is VoidEventWithParameters -> event.parametersClass
        is InstanceEventWithParameters -> event.parametersClass
        else -> return null
    }

    logger.debug("Parameters class: {}", parametersClass)
    try {
        val schema = ObjectSchema.of(parametersClass as kotlin.reflect.KClass<*>)
        val values = mutableMapOf<String, Any?>()
        for (field in schema.fields) {
            val requestParamValue = request.arguments?.get(field.name)
                ?: throw IllegalArgumentException("Missing parameter for tool call ${request.name}: ${field.name}")

            if (requestParamValue !is JsonPrimitive) {
                throw IllegalArgumentException("Unknown JSON class ${requestParamValue.javaClass.simpleName}")
            }
            if (requestParamValue is JsonNull) {
                values[field.name] = null
                continue
            }
            val content = requestParamValue.content
            values[field.name] = when (field.type) {
                PropertyType.Ref -> ModelID<Any>(content.toInt())
                PropertyType.String -> field.createContainer(content)
                PropertyType.Int -> field.createContainer(content.toInt())
                PropertyType.Long -> field.createContainer(content.toLong())
                PropertyType.Short -> field.createContainer(content.toShort())
                PropertyType.Byte -> field.createContainer(content.toByte())
                PropertyType.UInt -> field.createContainer(content.toUInt())
                PropertyType.ULong -> field.createContainer(content.toULong())
                PropertyType.UShort -> field.createContainer(content.toUShort())
                PropertyType.UByte -> field.createContainer(content.toUByte())
                PropertyType.Float -> field.createContainer(content.toFloat())
                PropertyType.Double -> field.createContainer(content.toDouble())
                PropertyType.Boolean -> field.createContainer(content.toBoolean())
                PropertyType.Enum -> field.createContainer(field.enumConstants.first { it.name == content })
                else -> throw IllegalArgumentException("Unsupported parameter type ${field.type} of ${field.name}")
            }
        }
        return schema.create(values)
    } catch (e: Exception) {
        throw IllegalArgumentException("Error instantiating parameters class: ${parametersClass.simpleName}", e)
    }
}

private suspend fun <T : Any, ModelStates : Enum<*>, C : KlerkContext, V> handleToolRequest(
    stateMachine: StateMachine<T, ModelStates, C, V>,
    klerk: Klerk<C, V>,
    event: Event<Any, Any?>,
    contextProvider: ContextProvider<C>,
    request: CallToolRequest,
): CallToolResult {
    logger.debug("Handling tool request for event: {}", event)
    logger.debug("State machine: {}", stateMachine)

    val paramsInstance = createCommandParams(event, request)

    val modelIdForCommand: ModelID<T>? = request.arguments?.get(MODEL_ID_JSON_PARAMETER)?.let { modelIdJsonParam ->
        if (modelIdJsonParam !is JsonPrimitive) {
            throw IllegalArgumentException("Unknown JSON class ${modelIdJsonParam.javaClass.simpleName}")
        }
        ModelID(modelIdJsonParam.content.toInt())
    }

    // Create and execute the command
    @Suppress("UNCHECKED_CAST")
    val command = Command.dynamic(event as Event<T, Any?>, modelIdForCommand, paramsInstance)

    // Create a context for the command
    val context = contextProvider(command) // todo: fix model

    // Handle the command
    when(val result = klerk.handle(command, context)) {
        is Failure -> {
            logger.error("Command execution failed: {}", result.problems.joinToString(", "))
            return CallToolResult(
                content = listOf(TextContent("Error: ${result.problems.joinToString(", ")}")),
            )
        }
        is Success -> {
            logger.info("Command executed successfully")
            val modelId = result.primaryModel

            if (result.deletedModels.any { it == result.primaryModel }) {
                // The model was probably deleted.
                return CallToolResult(
                    content = listOf(TextContent("Successfully executed tool ${request.name}")),
                )
            }

            if (modelId != null) {
                val model = klerk.read(context) { get(modelId) }
                return CallToolResult(
                    content = listOf(
                        TextContent("Successfully executed tool ${request.name}"),
                        TextContent(modelToJson(model).toString()),
                    ),
                )
            } else {
                return CallToolResult(
                    content = listOf(TextContent("Command executed successfully")),
                )
            }
        }
    }
}

/**
 * Converts a Klerk model to a JsonObject to return to the MCP client
 */
internal fun modelToJson(model: Model<*>): JsonObject {
    val props = model.props
    val propsMap = ObjectSchema.of(props::class).fields.associate { it.name to propertyToJson(it.get(props)) }

    return buildJsonObject {
        put("id", JsonPrimitive(model.id.toString()))
        put("state", JsonPrimitive((model.state)))
        put("props", JsonObject(propsMap))
    }
}

private fun propertyToJson(
    value: Any?,
) : JsonElement {
    return when (value) {
        // Handle DataContainer types which have a 'value' property
        is DataContainer<*> -> {
            JsonPrimitive(value.toString())
        }
        // Handle ModelID
        is ModelID<*> -> {
            JsonPrimitive(value.toString())
        }
        // Handle other primitive types
        is String, is Int, is Boolean, is Long, is Float, is Double -> {
            JsonPrimitive(value.toString())
        }
        is List<*>, is Set<*> -> {
            buildJsonArray {
                for (element in (value as Iterable<*>)) {
                    add(propertyToJson(element))
                }
            }
        }
        null -> {
            JsonNull
        }
        else -> {
            throw IllegalArgumentException("Unsupported property type: ${value::class}")
        }
    }
}

// TODO: provide a "hello" response for /mcp if accept-header is text/html
