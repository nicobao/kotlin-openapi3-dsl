package cc.vileda.openapi3

import cc.vileda.openapi3.OpenApi.Companion.mapper
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator
import com.reprezen.kaizen.oasparser.OpenApi3Parser
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

data class Info(
        var title: String = "",
        var version: String = ""
)

interface Schema {
    val schema: String
}

interface ParameterSchema {
    val schema: String
}

private data class SchemaGen(val schemaJson: JSONObject, val schema: String)

private fun schemaFrom(clazz: Class<*>): SchemaGen {
    val schemaGen = JsonSchemaGenerator(mapper)
    val s = schemaGen.generateSchema(clazz)
    val jsonSchema = JSONObject(mapper.writeValueAsString(s))
    jsonSchema.remove("id")
    return SchemaGen(JSONObject(mapOf("schema" to jsonSchema)), "#/components/schemas/${clazz.simpleName}")
}

data class TypedSchema<T>(
        @field:JsonIgnore
        val clazz: Class<T>
) : Schema {
    override val schema: String
    @field:JsonIgnore
    val schemaJson: JSONObject

    init {
        val genSchema = schemaFrom(clazz)
        schemaJson = genSchema.schemaJson
        schema = genSchema.schema
    }
}

data class TypedParameterSchema<T>(
        @field:JsonIgnore
        val clazz: Class<T>
) : ParameterSchema {
    override val schema: String
    @field:JsonIgnore
    val schemaJson: JSONObject

    init {
        val genSchema = schemaFrom(clazz)
        schemaJson = genSchema.schemaJson
        schema = genSchema.schema
    }
}

class SchemaSerializer(mt: Class<Schema>? = null) : StdSerializer<Schema>(mt) {
    override fun serialize(value: Schema, gen: JsonGenerator, provider: SerializerProvider?) {
        gen.writeRawValue(JSONObject(mapOf("schema" to mapOf("\$ref" to value.schema))).toString())
    }
}

class ParameterSchemaSerializer(mt: Class<ParameterSchema>? = null) : StdSerializer<ParameterSchema>(mt) {
    override fun serialize(value: ParameterSchema, gen: JsonGenerator, provider: SerializerProvider?) {
        gen.writeRawValue(JSONObject(mapOf("\$ref" to value.schema)).toString())
    }
}

class ComponentsSerializer(mt: Class<Components>? = null) : StdSerializer<Components>(mt) {
    override fun serialize(value: Components, gen: JsonGenerator, provider: SerializerProvider?) {
        gen.writeRawValue(JSONObject().put("schemas", value.schemas).toString())
    }
}

data class SecurityRequirement(
        private var nameToRequirements: MutableMap<String, List<String>> = mutableMapOf()
) : MutableMap<String, List<String>> by nameToRequirements

data class Parameter(
        var name: String = "",
        var `in`: String = "path",
        var description: String = "",
        var required: Boolean = true,
        var style: String = "simple",
        var schema: TypedParameterSchema<*> = TypedParameterSchema(String::class.java)
) {
    inline fun <reified T> schema() {
        schema = TypedParameterSchema(T::class.java)
    }
}

data class Response(
        var description: String = ""
) {
    val content = HashMap<String, TypedSchema<*>>()
    inline fun <reified T> response(mediaType: String) {
        val apiMediaType = TypedSchema(T::class.java)
        content.put(mediaType, apiMediaType)
    }
}

data class RequestBody(
        val content: MutableMap<String, TypedSchema<*>> = HashMap()
) {
    var description: String? = null
    inline fun <reified T> request(mediaType: String) {
        val apiMediaType = TypedSchema(T::class.java)
        content.put(mediaType, apiMediaType)
    }
}

data class Responses(
        private val responses: MutableMap<String, Response> = HashMap()
) : MutableMap<String, Response> by responses

data class Operation(
        var description: String = "",
        var operationId: String = "",
        var tags: List<String> = emptyList(),
        var summary: String = "",
        var deprecated: Boolean = false,
        var servers: List<Server> = emptyList(),
        var externalDocs: ExternalDocumentation? = null,
        var security: List<SecurityRequirement> = emptyList()
) {
    val responses = Responses()
    var requestBody: RequestBody? = null
    var parameters: MutableList<Parameter>? = null
    fun code(code: String, init: Response.() -> Unit) {
        val response = Response()
        response.init()
        responses.put(code, response)
    }

    fun created(init: Response.() -> Unit) = code("201", init)
    fun ok(init: Response.() -> Unit) = code("200", init)

    fun requestBody(init: RequestBody.() -> Unit) {
        if (requestBody == null) {
            requestBody = RequestBody()
            requestBody!!.init()
        }
    }

    fun parameter(init: Parameter.() -> Unit) {
        if (parameters == null) {
            val parameter = Parameter()
            parameter.init()
            parameters = mutableListOf(parameter)
        }
    }
}

open class PathItem(
        @field:JsonIgnore
        val path: Operation,
        @field:JsonIgnore
        val jsonKey: String
)

data class Paths(
        private val paths: MutableMap<String, MutableMap<String, Operation>> = HashMap()
) : MutableMap<String, MutableMap<String, Operation>> by paths {
    private fun makeOperation(init: Operation.() -> Unit): Operation {
        val apiPath = Operation()
        apiPath.init()
        return apiPath
    }

    private fun putPath(path: String, pathItem: PathItem) {
        if (containsKey(path)) {
            get(path)?.put(pathItem.jsonKey, pathItem.path)
        } else {
            put(path, mutableMapOf(pathItem.jsonKey to pathItem.path))
        }
    }

    fun get(path: String, init: Operation.() -> Unit) {
        putPath(path, PathItem(makeOperation(init), "get"))
    }

    fun put(path: String, init: Operation.() -> Unit) {
        putPath(path, PathItem(makeOperation(init), "put"))
    }

    fun post(path: String, init: Operation.() -> Unit) {
        putPath(path, PathItem(makeOperation(init), "post"))
    }

    fun delete(path: String, init: Operation.() -> Unit) {
        putPath(path, PathItem(makeOperation(init), "delete"))
    }

    fun patch(path: String, init: Operation.() -> Unit) {
        putPath(path, PathItem(makeOperation(init), "patch"))
    }

    fun head(path: String, init: Operation.() -> Unit) {
        putPath(path, PathItem(makeOperation(init), "head"))
    }

    fun options(path: String, init: Operation.() -> Unit) {
        putPath(path, PathItem(makeOperation(init), "options"))
    }

    fun trace(path: String, init: Operation.() -> Unit) {
        putPath(path, PathItem(makeOperation(init), "trace"))
    }
}

data class Components(
        val schemas: Map<String, Any> = HashMap()
)

data class ExternalDocumentation(
        var url: String,
        var description: String = ""
)

data class Tag(
        var name: String = "",
        var description: String = "",
        var externalDocs: ExternalDocumentation? = null
)

data class ServerVariable(
        var default: String,
        var enum: List<String> = emptyList(),
        var description: String = ""
)

data class Server(
        var url: String = "",
        var description: String = "",
        val variables: Map<String, ServerVariable> = emptyMap()
)

data class OpenApi(
        var openapi: String = "3.0.0",
        var info: Info = Info(),
        var paths: Paths = Paths(),
        var tags: List<Tag> = emptyList(),
        var externalDocs: ExternalDocumentation? = null,
        var servers: List<Server> = mutableListOf()
) {
    companion object {
        @field:JsonIgnore
        val mapper = ObjectMapper()
    }

    init {
        val module = SimpleModule()
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        module.addSerializer(Schema::class.java, SchemaSerializer())
        module.addSerializer(ParameterSchema::class.java, ParameterSchemaSerializer())
        module.addSerializer(Components::class.java, ComponentsSerializer())
        mapper.registerModule(module)
    }

    val components: Components
        get() {
            val responseSchemas: Map<String, Any> = paths.values
                    .flatMap { it.values }
                    .flatMap { it.responses.values }
                    .flatMap { it.content.values }
                    .fold(mutableMapOf()) { m, o ->
                        m.put(o.clazz.simpleName, o.schemaJson.getJSONObject("schema"))
                        m
                    }

            val requestSchemas: Map<String, Any> = paths.values
                    .flatMap { it.values }
                    .mapNotNull { it.requestBody }
                    .flatMap { it.content.values }
                    .fold(mutableMapOf()) { m, o ->
                        m.put(o.clazz.simpleName, o.schemaJson.getJSONObject("schema"))
                        m
                    }

            val parameterSchemas: Map<String, Any> = paths.values
                    .flatMap { it.values }
                    .mapNotNull { it.parameters }
                    .flatMap { it }
                    .map { it.schema }
                    .fold(mutableMapOf()) { m, o ->
                        m.put(o.clazz.simpleName, o.schemaJson.getJSONObject("schema"))
                        m
                    }

            return Components(responseSchemas
                    .plus(requestSchemas)
                    .plus(parameterSchemas))
        }

    fun info(init: Info.() -> Unit) {
        info.init()
    }

    fun paths(init: Paths.() -> Unit) {
        paths.init()
    }

    private fun toJson(): JSONObject {
        val writeValueAsString = mapper.writeValueAsString(this)
        return JSONObject(writeValueAsString)
    }

    fun asJson(): JSONObject {
        val parse = OpenApi3Parser().parse(asFile())
        return JSONObject(parse.toJson().toString())
    }

    fun asFile(): File {
        val file = Files.createTempFile("openapi-", ".json").toFile()
        file.writeText(toJson().toString())
        file.deleteOnExit()
        return file
    }
}

fun openapi3(init: OpenApi.() -> Unit): OpenApi {
    val openapi3 = OpenApi()
    openapi3.init()
    return openapi3
}