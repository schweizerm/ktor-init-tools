package io.ktor.start.swagger

import io.ktor.start.*
import io.ktor.start.features.server.*
import io.ktor.start.project.*
import io.ktor.start.swagger.SwaggerGeneratorInterface.doc
import io.ktor.start.util.*

object SwaggerGeneratorRaw : SwaggerGeneratorBase() {

    fun BlockBuilder.registerRoutes(info: BuildInfo, model: SwaggerModel, arguments: SwaggerArguments) {
        addImport("io.ktor.swagger.experimental.*")
        addSwaggerUtils(info)
        addRoute {
            +"${model.info.classNameServer}(${arguments.instances}).apply" {
                for (tag in model.tags) {
                    +"${tag.registerMethodName}()"
                }
            }
        }
    }

    fun BlockBuilder.fileSwaggerBackendHandler(
        fileName: String,
        info: BuildInfo,
        model: SwaggerModel,
        arguments: SwaggerArguments
    ) {
        fileText(fileName) {
            SEPARATOR {
                +"package ${info.artifactGroup}"
            }

            SEPARATOR {
                +"import io.ktor.application.*"
                +"import io.ktor.response.*"
                +"import io.ktor.routing.*"
                +"import java.util.*"
                +"import io.ktor.swagger.experimental.*"
                +"import io.ktor.auth.*"
                +"import io.ktor.http.*"
            }

            SEPARATOR {
                doc(title = model.info.title, description = model.info.description)
                +"class ${model.info.classNameServer}(${arguments.decls})" {
                    val processedMethods = hashSetOf<SwaggerModel.PathMethodModel>()
                    for (tag in model.tags) {
                        SEPARATOR {
                            doc(tag.tag)
                            +"fun Routing.${tag.registerMethodName}()" {
                                for (route in model.routes.values) {
                                    for (method in route.methodsList) {
                                        if (!method.processedTags.contains(tag)) continue // Not containing this tag
                                        if (method in processedMethods) continue // Already processed
                                        processedMethods += method

                                        SEPARATOR {
                                            if (method.security.isNotEmpty()) {
                                                +"authenticate(${method.security.joinToString(", ") { it.name.quote() }})" {
                                                    route(method, route)
                                                }
                                            } else {
                                                route(method, route)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun Indenter.route(
        method: SwaggerModel.PathMethodModel,
        route: SwaggerModel.PathModel
    ) {
        +"${method.method}(${route.path.quote()})" {
            // Parameter reading
            SEPARATOR {
                for ((pinfo, param) in method.parameters.metaIter) {
                    val pname = param.name
                    val qpname = pname.quote()
                    val ptype = param.schema.toKotlinType()
                    val inAnnotation = when (param.inside) {
                        SwaggerModel.Inside.BODY -> "call.getBodyParam<$ptype>($qpname)"
                        SwaggerModel.Inside.HEADER -> "call.getHeader<$ptype>($qpname)"
                        SwaggerModel.Inside.QUERY -> "call.getQuery<$ptype>($qpname)"
                        SwaggerModel.Inside.PATH -> "call.getPath<$ptype>($qpname)"
                        SwaggerModel.Inside.FORM_DATA -> "call.getFormData<$ptype>($qpname)"
                    }
                    val default = if (param.required) {
                        //"{ error(" + "mandatory $pname".quote() + ") }"
                        ""
                    } else {
                        "{ " + indentStringHere {
                            toKotlinDefault(param.schema, param.default, typed = true)
                        } + " }"
                    }
                    +"val ${param.name} = $inAnnotation $default"
                }
            }
            SEPARATOR {
                val untyped = routeBodyCheckParameters(method)
                +"call.respond(${indentString(indentLevel) {
                    toKotlinDefault(method.responseType, untyped, typed = true)
                }})"
            }
        }
    }

    fun BlockBuilder.fileSwaggerFrontendHandler(fileName: String, info: BuildInfo, model: SwaggerModel) {
        fileText(fileName) {
            SEPARATOR {
                +"package ${info.artifactGroup}"
            }

            SEPARATOR {
                +"import io.ktor.client.*"
                +"import io.ktor.client.request.*"
            }

            SEPARATOR {
                +"typealias Date = String"
            }

            SEPARATOR {
                doc(title = model.info.title + " Client", description = model.info.description)
                +"open class ${model.info.classNameClient}(val endpoint: String)" {

                    +"val client: HttpClient"
                    +"init" {
                        +"this.client = actualHttpClient.config " {
                            +"install(JsonFeature)" {
                                +"serializer = KotlinSerializer().apply" {
                                    val parameterTypes = model.routesList
                                        .map { it.methodsList }
                                        .flatten()
                                        .asSequence()
                                        .map { it.parametersBody }
                                        .flatten()
                                        .map { it.schema.type.toKotlinType() }

                                    val models = model.definitions.values
                                        .map { it.name }

                                    model.routesList
                                        .map { it.methodsList }
                                        .flatten()
                                        .asSequence()
                                        .map { it.responseType.toKotlinType() }
                                        .plus(parameterTypes)
                                        .plus(models)
                                        .distinct()
                                        .sorted()
                                        .forEach {
                                            if (isListType(it)) {
                                                +"registerList(${getListType(it)}.serializer().list)"
                                            } else {
                                                +"setMapper($it::class, $it.serializer())"
                                            }
                                        }

                                    /*for (method in route.methodsList) {
                                        val responseType = method.responseType.toKotlinType()
                                    }
                                }*/
                                }
                            }
                        }
                    }

                    for (route in model.routes.values) {
                        for (method in route.methodsList) {
                            var responseType = method.responseType.toKotlinType()
                            val resultType = responseType
                            val isListType = isListType(responseType)
                            if (isListType) {
                                responseType = "String"
                            }
                            SEPARATOR {
                                doc(
                                    title = "",
                                    description = method.summaryDescription,
                                    params = method.parameters.associate { it.name to it.description },
                                    retval = method.defaultResponse.description
                                )
                                +"fun ${method.methodName}("
                                indent {
                                    for ((pinfo, param) in method.parameters.metaIter) {
                                        val qpname = param.name.quote()
                                        val default = if (param.required) "" else "? = null"
                                        +"${param.name}: ${param.schema.toKotlinType()}$default, // ${param.inside}"
                                    }
                                    +"callback: (result: $resultType?, error: ApiException?) -> Unit"
                                }
                                +")" {
                                    val replacedPath = method.path.replace(Regex("\\{(\\w+)\\}")) {
                                        "\$" + it.groupValues[1]
                                    }
                                    +"launchAndCatch(localDefaultDispatcher, localMainDispatcher, { callback(null, it) }, {"
                                    indent {
                                        +"val result = client.${method.method}<$responseType>(\"\$endpoint$replacedPath\")" {
                                            if (method.parametersQuery.isNotEmpty()) {
                                                +"this.url" {
                                                    +"this.parameters.apply" {
                                                        for (param in method.parametersQuery) {
                                                            val nullable = if (param.required) "" else "?"
                                                            val appendText = if (param.schema.toKotlinType().contains("List")) {
                                                                val appendValue = if (param.required) param.name else "it"
                                                                "this.append(${param.name.quote()}, $appendValue.joinToString(\",\"))"
                                                            } else {
                                                                val appendValue = if (param.required)
                                                                    (if (param.schema.toKotlinType() == "String") param.name else "\"\$${param.name}\"") else (if (param.schema.toKotlinType() == "String") "it" else "\"\$it\"")
                                                                "this.append(${param.name.quote()}, $appendValue)"
                                                            }

                                                            if (param.required) {
                                                                +appendText
                                                            } else {
                                                                +"${param.name}$nullable.let { $appendText }"
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            if (method.parametersBody.isNotEmpty()) {
                                                +"this.body = serializerKotlin.write(${method.parametersBody[0].name})"
                                            }
                                        }
                                        if (isListType) {
                                            +"val listResult = json.parse(${getListType(method.responseType.toKotlinType())}.serializer().list, result)"
                                        }
                                        +"localMainDispatcher.launch {"
                                        indent {
                                            if (isListType) {
                                                +"callback(listResult, null)"
                                            } else {
                                                +"callback(result, null)"
                                            }
                                        }
                                        +"}"
                                    }
                                    +"})"
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun BlockBuilder.fileSwaggerDtos(fileName: String, info: BuildInfo, model: SwaggerModel) {
        fileText(fileName) {
            SEPARATOR {
                +"package ${info.artifactGroup}"
            }
            SEPARATOR {
                +"import java.util.*"
                +"import io.ktor.http.*"
                +"import io.ktor.request.*"
                +"import io.ktor.swagger.experimental.*"
            }
            SEPARATOR {
                swaggerDtos(info, model)
            }
        }
    }

    private fun isListType(type: String): Boolean {
        return type.startsWith("List<")
    }

    /**
     * Would return MyClass for List<MyClass>
     */
    private fun getListType(type: String): String {
        return Regex("List<(.+)>").find(type)?.groupValues?.get(1) ?: "ERROR"
    }
}
