package io.bazel.kotlin.generate

import io.bazel.kotlin.generate.WriteKotlincCapabilities.KotlincCapabilities.Companion.asCapabilities
import org.jetbrains.kotlin.cli.common.arguments.Argument
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.load.java.JvmAbi
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.time.Year
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.jvm.java
import kotlin.math.max
import kotlin.streams.asSequence
import kotlin.streams.toList

/**
 * Generates a list of kotlinc flags from the K2JVMCompilerArguments on the classpath.
 */
object WriteKotlincCapabilities {

  @JvmStatic
  fun main(vararg args: String) {
    // TODO: Replace with a real option parser
    val options = args.asSequence()
      .flatMap { t -> t.split("=", limit = 1) }
      .chunked(2)
      .fold(mutableMapOf<String, MutableList<String>>()) { m, (key, value) ->
        m.apply {
          computeIfAbsent(key) { mutableListOf() }.add(value)
        }
      }

    val envPattern = Regex("\\$\\{(\\w+)}")
    val capabilitiesDirectory = options["--out"]
      ?.first()
      ?.let { env ->
        envPattern.replace(env) {
          System.getenv(it.groups[1]?.value)
        }
      }
      ?.run(FileSystems.getDefault()::getPath)
      ?.apply {
        if (!parent.exists()) {
          Files.createDirectories(parent)
        }
      }
      ?: error("--out is required")

    capabilitiesDirectory.resolve(capabilitiesName).writeText(
      getArguments(K2JVMCompilerArguments::class.java)
        .filterNot(KotlincCapability::shouldSuppress)
        .asCapabilities()
        .asCapabilitiesBzl()
        .toString(),
      StandardCharsets.UTF_8,
    )

    capabilitiesDirectory.resolve("templates.bzl").writeText(
      BzlDoc {
        assignment(
          "TEMPLATES",
          list(
            *Files.list(capabilitiesDirectory)
              .filter { it.fileName.toString().startsWith("capabilities_") }
              .map { "Label(${it.fileName.bzlQuote()})" }
              .sorted()
              .toArray(::arrayOfNulls),
          ),
        )
      }.toString(),
    )
  }

  /** Options that are either confusing, useless, or unexpected to be set outside the worker. */
  private val suppressedFlags = setOf(
    "-P",
    "-X",
    "-Xbuild-file",
    "-Xcompiler-plugin",
    "-Xdump-declarations-to",
    "-Xdump-directory",
    "-Xdump-fqname",
    "-Xdump-perf",
    "-Xintellij-plugin-root",
    "-Xplugin",
    "-classpath",
    "-d",
    "-expression",
    "-help",
    "-include-runtime",
    "-jdk-home",
    "-kotlin-home",
    "-module-name",
    "-no-jdk",
    "-no-stdlib",
    "-script",
    "-script-templates",
  )

  fun String.increment() = "$this  "
  fun String.decrement() = substring(0, (length - 2).coerceAtLeast(0))

  val capabilitiesName: String by lazy {
    LanguageVersion.LATEST_STABLE.run {
      "capabilities_${major}.${minor}.bzl.com_github_jetbrains_kotlin.bazel"
    }
  }

  private class BzlDoc {
    private val HEADER = Comment(
      """
    # Copyright ${Year.now()} The Bazel Authors. All rights reserved.
    #
    # Licensed under the Apache License, Version 2.0 (the "License");
    # you may not use this file except in compliance with the License.
    # You may obtain a copy of the License at
    #
    #    http://www.apache.org/licenses/LICENSE-2.0
    #
    # Unless required by applicable law or agreed to in writing, software
    # distributed under the License is distributed on an "AS IS" BASIS,
    # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    # See the License for the specific language governing permissions and
    # limitations under the License.
    
    # DO NOT EDIT: generated by bazel run //src/main/kotlin/io/bazel/kotlin/generate:kotlin_release_options
  """.trimIndent(),
    )

    val contents: MutableList<Block> = mutableListOf()

    constructor(statements: BzlDoc.() -> Unit) {
      statement(HEADER)
      apply(statements)
    }

    fun statement(vararg statements: Block) {
      contents.addAll(statements)
    }

    class Indent(val spaces: Int = 0) {
      fun increment() = Indent(spaces+2)
      fun decrement() = Indent(max(spaces - 2, 0))
      override fun toString() = " ".repeat(spaces)
      operator fun plus(s:String?) = toString() + s
    }

    fun interface Block {
      fun asString(indent: Indent): String?
      fun asString() = asString(Indent())
    }

    fun interface ValueBlock : Block {
      fun asString(indent: Indent, map: (String) -> String): String?
      override fun asString(indent: Indent) = asString(indent.increment()) { it }
    }

    class Comment(val contents: String) : Block {
      override fun asString(indent: Indent): String? = indent + contents
    }

    override fun toString() = contents.mapNotNull { it.asString() }.joinToString("\n")

    fun assignment(key: String, value: ValueBlock) {
      statement(
        Block { indent ->
          indent + value.asString(indent.increment()) { "$key = $it" }
        },
      )
    }

    fun struct(vararg properties: Pair<String, String?>) = ValueBlock { indent, format ->
      properties
        .mapNotNull { (key, value) ->
          value?.let { "$indent$key = $it" }
        }
        .joinToString(",\n", prefix = "struct(\n", postfix = "\n${indent.decrement()})")
        .run(format)
    }

    fun dict(vararg properties: Pair<String, ValueBlock>) = ValueBlock { indent, format ->
      properties
        .mapNotNull { (key, value) ->
          value.asString(indent.increment())
            ?.let { "$indent${key.bzlQuote()} : $it" }
        }
        .joinToString(",\n", prefix = "{\n", postfix = "\n${indent.decrement()}}")
        .run(format)
    }

    fun list(vararg items: String) = ValueBlock { indent, format ->
      items
        .joinToString(
            separator = ",\n",
            prefix = "[\n",
            postfix = "\n${indent.decrement()}]",
        ) { "$indent$it" }
        .run(format)
    }
  }

  private fun getArguments(klass: Class<*>): Sequence<KotlincCapability> = sequence {
    val instance = K2JVMCompilerArguments()
    klass.superclass?.let {
      yieldAll(getArguments(it))
    }

    for (field in klass.declaredFields) {
      field.getAnnotation(Argument::class.java)?.let { argument ->
        val getter = klass.getMethod(JvmAbi.getterName(field.name))
        yield(
          KotlincCapability(
            flag = argument.value,
            default = getter.invoke(instance)?.let(Any::toString),
            type = StarlarkType.mapFrom(field.type),
            doc = argument.description,
          ),
        )
      }
    }
  }

  private class KotlincCapabilities(val capabilities: Iterable<KotlincCapability>) {

    companion object {
      fun Sequence<KotlincCapability>.asCapabilities() = KotlincCapabilities(sorted().toList())
    }

    fun asCapabilitiesBzl() = BzlDoc {
      assignment(
        "KOTLIN_OPTS",
        dict(
          *capabilities.map { capability ->
            capability.flag to struct(
              "flag" to capability.flag.bzlQuote(),
              "doc" to capability.doc.bzlQuote(),
              "default" to capability.defaultStarlarkValue(),
            )
          }.toTypedArray(),
        ),
      )
    }
  }

  data class KotlincCapability(
    val flag: String,
    val doc: String,
    private val default: String?,
    private val type: StarlarkType,
  ) : Comparable<KotlincCapability> {

    fun shouldSuppress() = flag in suppressedFlags

    fun defaultStarlarkValue(): String? = type.convert(default)

    override fun compareTo(other: KotlincCapability): Int = flag.compareTo(other.flag)
  }

  sealed class StarlarkType(val attr: String) {

    class Bool() : StarlarkType("attr.bool") {
      override fun convert(value: String?): String? = when (value) {
        "true" -> "True"
        else -> "False"
      }
    }

    class Str() : StarlarkType("attr.string") {
      override fun convert(value: String?): String? = value?.bzlQuote() ?: "None"
    }

    class StrList() : StarlarkType("attr.string_list") {
      override fun convert(value: String?): String =
        value?.let { "default = [${it.bzlQuote()}]" } ?: "[]"
    }

    companion object {
      fun mapFrom(clazz: Class<*>) = when (clazz.canonicalName) {
        java.lang.Boolean.TYPE.canonicalName, java.lang.Boolean::class.java.canonicalName -> Bool()
        java.lang.String::class.java.canonicalName -> Str()
        Array<String>::class.java.canonicalName -> StrList()
        else -> {
          throw IllegalArgumentException("($clazz)is not a starlark mappable type")
        }
      }
    }

    abstract fun convert(value: String?): String?
  }

  private fun Any.bzlQuote(): String {
    var asString = toString()
    val quote = "\"".repeat(if ("\n" in asString || "\"" in asString) 3 else 1)
    return quote + asString + quote
  }
}
