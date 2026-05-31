package dev.klerkframework.mcp

import dev.klerkframework.klerk.*
import dev.klerkframework.klerk.CommandResult.Failure
import dev.klerkframework.klerk.CommandResult.Success
import dev.klerkframework.klerk.command.Command
import dev.klerkframework.klerk.command.CommandToken
import dev.klerkframework.klerk.command.ProcessingOptions
import dev.klerkframework.klerk.datatypes.DataContainer
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
 * A function that provides a context for executing commands.
 * This is typically used to create a context based on the current request or user session.
 *
 * @param C The type of KlerkContext that will be provided, typically a class named Ctx
 * @param command The command being executed, or null if no specific command is associated with this context request
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
            version = mcpServerVersion
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                resources = ServerCapabilities.Resources(subscribe = null, listChanged = null),
                tools = ServerCapabilities.Tools(listChanged = null),
            )
        )
    )

    for (model in klerk.config.managedModels) {
        val stateMachine = model.stateMachine

        stateMachine.getAllEvents().forEach { eventReference ->
            logger.debug("Adding tool for model {} and event: {}",model.kClass.simpleName, eventReference.eventName)

            val event = klerk.config.getEvent(eventReference)

            val required: MutableList<String> = mutableListOf()
            val properties: MutableMap<String, JsonElement> = mutableMapOf()

            if (event is InstanceEvent) {
                required.add(MODEL_ID_JSON_PARAMETER)
                properties[MODEL_ID_JSON_PARAMETER] = JsonObject(mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Model ID (as base-36 encoded string) of the instance to execute the command on"),
                    )
                )
            }

            klerk.config.getParameters(eventReference)?.let { parameters ->
                required.addAll(parameters.requiredParameters.map { it.name })
                parameters.all.forEach { eventParameter ->
                    properties[eventParameter.name] = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive(propertyTypeToJsonType(eventParameter.type)),
                            "description" to JsonPrimitive("Value for the ${eventParameter.valueClass.simpleName}"),
                        )
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
                handleToolRequest(stateMachine, klerk, klerk.config.getEvent(eventReference), contextProvider, request)
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
                listIfAuthorized(model.collections.all)
            }

            val jsonArray = buildJsonArray {
                models.map { modelToJson(it) }.forEach { add(it) }
            }

            ReadResourceResult(
                contents = listOf(
                    TextResourceContents(jsonArray.toString(), request.uri, "application/json")
                )
            )
        }

        // Because MCP clients support for resources are limited, it more compatible to add a tool for listing models.
        server.addTool(
            name = "${toSnakeCase(model.kClass.simpleName!!)}_list",
            description = "Lists all ${model.kClass.simpleName!!} models",
        ) { request ->
            val models = klerk.read(contextProvider(null)) {
                listIfAuthorized(model.collections.all)
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
        PropertyType.Int ->     "number"
        PropertyType.Long ->    "number"
        PropertyType.Float ->   "number"
        PropertyType.Boolean -> "boolean"
        PropertyType.Ref ->     "string"
        PropertyType.KeyValueRef -> "string"
        PropertyType.Instant -> "string"
        PropertyType.Duration -> "string"
        PropertyType.Geo -> "string"
        PropertyType.Enum ->    throw IllegalArgumentException("PropertyType.Enum not yet implemented")
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
 * Build a Command params instance using the request arguments from the MCP client.
 * @return The created Command params instance, or null if the event has no parameters.
 */
private fun createCommandParams(event: Event<Any, Any?>, request: CallToolRequest): Any? {
    val parametersClass = when(event) {
        is VoidEventWithParameters -> event.parametersClass
        is InstanceEventWithParameters -> event.parametersClass
        else -> return null
    }

    logger.debug("Parameters class: {}", parametersClass)
    try {
        // Get the constructor of the parameters class
        val constructor = parametersClass.constructors.firstOrNull()
            ?: throw IllegalStateException("No constructor found for $parametersClass")

        // Get constructor parameters
        val constructorParams = constructor.parameters

        // Create a map to hold the parameter values we'll pass to the constructor
        val paramValues = mutableMapOf<kotlin.reflect.KParameter, Any>()

        // Process each constructor parameter
        for (param in constructorParams) {
            val paramName = param.name ?: continue
            val paramType = param.type.classifier as? kotlin.reflect.KClass<*> ?: continue
            val requestParamValue = request.arguments?.get(paramName)
                ?: throw IllegalArgumentException("Missing parameter for tool call ${request.name}: $paramName")

            if (requestParamValue !is JsonPrimitive) {
                throw IllegalArgumentException("Unknown JSON class ${requestParamValue.javaClass.simpleName}")
            }

            // Handle ModelID parameters
            if (paramType == ModelID::class) {
                paramValues[param] = ModelID<Any>(requestParamValue.content.toInt()) as Any
                continue
            }

            // Find the constructor of the parameter type (which should be a DataContainer subclass)
            val containerConstructor = paramType.constructors.firstOrNull()
                ?: throw IllegalStateException("No constructor found for parameter type $paramType")

            // Get the first parameter of the constructor to determine what type it expects
            val constructorFirstParam = containerConstructor.parameters.firstOrNull()
                ?: throw IllegalStateException("Constructor for $paramType has no parameters")

            // Create an instance of the DataContainer subclass with the value from the request
            val containerInstance = when (val parameterType = constructorFirstParam.type.classifier) {
                String::class -> {
                    containerConstructor.call(requestParamValue.content)
                }
                Int::class -> {
                    val intValue = requestParamValue.content.toInt()
                    containerConstructor.call(intValue)
                }
                Boolean::class -> {
                    val boolValue = requestParamValue.content.toBoolean()
                    containerConstructor.call(boolValue)
                }
                else -> {
                    throw IllegalArgumentException("Unsupported parameter type: $paramType with constructor parameter type: $parameterType")
                }
            }
            paramValues[param] = containerInstance
        }

        // Create an instance of the parameters class with the constructed parameter values
        return constructor.callBy(paramValues)
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
    val command = Command(
        event = event as Event<T, Any?>,
        model = modelIdForCommand,
        params = paramsInstance
    )

    // Create a context for the command
    val context = contextProvider(command) // todo: fix model

    // Handle the command
    when(val result = klerk.handle(command, context, ProcessingOptions(CommandToken.simple()))) {
        is Failure -> {
            logger.error("Command execution failed: {}", result.problems.joinToString(", "))
            return CallToolResult(
                content = listOf(TextContent("Error: ${result.problems.joinToString(", ")}"))
            )
        }
        is Success -> {
            logger.info("Command executed successfully")
            val modelId = result.primaryModel

            if (result.deletedModels.isNotEmpty() && result.deletedModels[0] == result.primaryModel) {
                // The model was probably deleted.
                return CallToolResult(
                    content = listOf(TextContent("Successfully executed tool ${request.name}"))
                )
            }

            if (modelId != null) {
                val model = klerk.read(context) { get(modelId) }
                return CallToolResult(
                    content = listOf(
                        TextContent("Successfully executed tool ${request.name}"),
                        TextContent(modelToJson(model).toString()),
                    )
                )
            } else {
                return CallToolResult(
                    content = listOf(TextContent("Command executed successfully"))
                )
            }
        }
    }
}

/**
 * Converts a Klerk model to a JsonObject to return to the MCP client
 */
internal fun modelToJson(model: Model<*>): JsonObject {
    val propsMap: MutableMap<String, JsonElement> = mutableMapOf()

    // Use reflection to get all properties from model.props
    val propsObj = model.props
    val propsClass = propsObj::class

    // Get all properties from the model.props object
    propsClass.members.forEach { member ->
        if (member is kotlin.reflect.KProperty1<*, *>) {
            try {
                // Cast to KProperty1<Any, *> to be able to get the value
                @Suppress("UNCHECKED_CAST")
                val prop = member as kotlin.reflect.KProperty1<Any, *>
                propsMap[member.name] = propertyToJson(prop.get(propsObj))

            } catch (e: Exception) {
                // Log the error but continue processing other properties
                logger.error("Error processing property ${member.name}: ${e.message}")
            }
        }
    }

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
                (value as Iterable<*>).forEach {
                    add(propertyToJson(it))
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
