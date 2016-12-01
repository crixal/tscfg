package tscfg.generators.scala

import java.io.{FileWriter, PrintWriter}
import tscfg.generators.{GenOpts, GenResult, Generator, durationUtil}
import scalaUtil._
import tscfg.specs._
import tscfg.specs.types._
import tscfg.{Key, util}

import scala.annotation.tailrec
import scala.collection.mutable

class ScalaGenerator(genOpts: GenOpts) extends Generator(genOpts) {

  val rootDefinedListElemAccessors: mutable.LinkedHashSet[(String, String)] = collection.mutable.LinkedHashSet[(String,String)]()

  def generate(objSpec: ObjSpec): GenResult = {

    rootDefinedListElemAccessors.clear()

    var results = GenResult()

    def genForObjSpec(objSpec: ObjSpec, indent: String, isRoot: Boolean = false): Code = {
      var comma = ""

      val className = getClassName(objSpec.key.simple)

      val orderedNames = objSpec.orderedNames
      val padScalaIdLength = if (orderedNames.nonEmpty)
        orderedNames.map(scalaIdentifier).maxBy(_.length).length else 0
      def padScalaId(id: String) = id + (" " * (padScalaIdLength - id.length))

      val code = Code(objSpec)

      // <case class>
      results = results.copy(classNames = results.classNames + className)
      code.println(indent + s"case class $className(")
      comma = ""
      orderedNames foreach { name =>
        val scalaId = scalaIdentifier(name)
        results = results.copy(fieldNames = results.fieldNames + scalaId)
        code.print(comma)
        code.print(s"$indent  ${padScalaId(scalaId)} : ")  // note, space before : for proper tokenization
        objSpec.children(name) match {
          case a:AtomicSpec ⇒
            code.print(getScalaType(a))

          case o:ObjSpec ⇒
            val base = className + "." + o.key.parts.map(getClassName).mkString(".")
            val cn = if (o.isOptional) s"scala.Option[$base]" else base
            code.print(cn)

          case l:ListSpec ⇒
            code.print(getScalaType(l, Some(className)))
        }
        comma = ",\n"
      }

      code.println(s"\n$indent)")

      code.println("")
      // </case class>

      // <object>
      code.println(indent + s"object $className {")

      // <recurse>
      orderedNames foreach { name =>
        objSpec.children(name) match {
          case o: ObjSpec ⇒
            val c = genCode(o, indent + IND)
            code.println(c.definition)

          case l:ListSpec ⇒
            val c = genCode(l.elemSpec, indent + IND)
            if (c.definition.nonEmpty)
              code.println(c.definition)

          case _ ⇒
        }
      }
      // </recurse>

      // <apply>
      code.println(s"$indent  def apply(c: com.typesafe.config.Config): $className = {")
      code.println(s"$indent    $className(")

      comma = indent
      orderedNames foreach { name =>
        code.print(comma)
        instance(code, objSpec.children(name), name)
        comma = s",\n$indent"
      }
      code.println("")
      code.println(s"$indent    )")
      code.println(s"$indent  }")
      // </apply>

      // auxiliary methods:
      accessors.insertStaticAuxMethods(code, isRoot, indent + IND, results)

      code.print(s"$indent}")
      // </object>

      code
    }

    def genForAtomicSpec(spec: AtomicSpec): Code = {
      Code(spec)
    }

    def genForListSpec(listSpec: ListSpec, indent: String): Code = {
      @tailrec
      def getElementSpec(ls: ListSpec): Spec = ls.elemSpec match {
        case subListSpec: ListSpec ⇒ getElementSpec(subListSpec)
        case nonListSpec           ⇒ nonListSpec
      }

      val elemSpec = getElementSpec(listSpec)

      val elemCode = genCode(elemSpec, indent)
      val code = Code(listSpec)

      if (elemCode.definition.nonEmpty) code.println(elemCode.definition)

      code
    }

    def genCode(spec: Spec, indent: String = ""): Code = spec match {
      case spec: AtomicSpec    ⇒ genForAtomicSpec(spec)
      case spec: ObjSpec       ⇒ genForObjSpec(spec, indent)
      case spec: ListSpec      ⇒ genForListSpec(spec, indent)
    }

    val header = new StringBuilder()
    genOpts.preamble foreach { p =>
      header.append(s"// ${p.replace("\n", "\n// ")}\n\n")
    }
    header.append(s"package ${genOpts.packageName}\n\n")

    // main class:
    val elemSpec = genForObjSpec(objSpec, "", isRoot = true)

    results = results.copy(code = header.toString() + elemSpec.definition)

    results
  }

  /**
    * Captures code associated with a spec.
    */
  private case class Code(spec: Spec) {

    def println(str: String): Unit = defn.append(str).append('\n')

    def print(str: String): Unit = defn.append(str)

    def definition: String = defn.toString

    val objectDefinedListElemAccessors = collection.mutable.LinkedHashSet[(String,String)]()

    private val defn = new StringBuilder()
  }

  private val IND = "  "

  private def getScalaType(spec: Spec, classNameOpt: Option[String] = None): String = {
    spec match {
      case a: AtomicSpec ⇒
        a.typ match {
            case STRING   ⇒ if (spec.isOptional) "scala.Option[java.lang.String]" else "java.lang.String"
            case INTEGER  ⇒ if (spec.isOptional) "scala.Option[scala.Int]"     else "scala.Int"
            case LONG     ⇒ if (spec.isOptional) "scala.Option[scala.Long]"    else "scala.Long"
            case DOUBLE   ⇒ if (spec.isOptional) "scala.Option[scala.Double]"  else "scala.Double"
            case BOOLEAN  ⇒ if (spec.isOptional) "scala.Option[scala.Boolean]" else "scala.Boolean"
            case DURATION ⇒ if (spec.isOptional) "scala.Option[scala.Long]"    else "scala.Long"
        }

      case o: ObjSpec  ⇒
        val xx = o.key.parts.map(getClassName).mkString(".")
        classNameOpt match {
          case None ⇒ xx
          case Some(cn) ⇒ cn + "." + xx
        }

      case l: ListSpec  ⇒
        val elemType = getScalaType(l.elemSpec, classNameOpt)
        val base = s"scala.collection.immutable.List[$elemType]"
        if (l.isOptional) s"scala.Option[$base]" else base
      }
  }

  private def instance(code: Code, spec: Spec, path: String): Unit = {
    spec match {
      case a:AtomicSpec ⇒
        code.print(s"""      ${atomicInstance(a, path)}""")

      case o:ObjSpec ⇒
        code.print(s"""      ${objectInstance(o)}""")

      case l:ListSpec ⇒
        code.print(s"""      ${listInstance(code, l, path)}""")
    }
  }

  private def objectInstance(spec: ObjSpec): String = {
    val path = spec.key.simple
    val className = getClassName(path)
    if (spec.isOptional) {
      s"""if(c.$hasPath("$path")) scala.Some($className(c.getConfig("$path"))) else None"""
    }
    else s"""$className(c.getConfig("$path"))"""
  }

  private def listInstance(code: Code, spec: ListSpec, path: String): String = {
    val base = accessors._listMethodName(spec.elemSpec, Some(code)) + s"""(c.getList("$path"))"""
    if (spec.isOptional) {
      s"""if(c.$hasPath("$path")) scala.Some($base) else None"""
    }
    else base
  }

  private def atomicInstance(spec: AtomicSpec, path: String): String = {
    val getter = spec.typ match {
      case STRING    ⇒  s"""getString("$path")"""
      case INTEGER   ⇒  s"""getInt("$path")"""
      case LONG      ⇒  s"""getLong("$path")"""
      case DOUBLE    ⇒  s"""getDouble("$path")"""
      case BOOLEAN   ⇒  s"""getBoolean("$path")"""
      case DURATION  ⇒  durationUtil.getter(path, spec)
    }

    spec.defaultValue match {
      case Some(v) ⇒
        val value = if (spec.typ == DURATION) durationUtil.durationValue(v, spec)
        else if (spec.typ == STRING) "\"" + v + "\"" else v
        s"""if(c.$hasPath("$path")) c.$getter else $value"""

      case None if spec.isOptional ⇒
        s"""if(c.$hasPath("$path")) Some(c.$getter) else None"""

      case _ ⇒
        s"""c.$getter"""
    }
  }

  private object accessors {
    def _listMethodName(spec: Spec, objCodeOpt: Option[Code] = None): String = {
      val elemAccessor = spec match {
        case a: AtomicSpec ⇒
          a.typ match {
            case STRING   ⇒ methodNames.strA
            case INTEGER  ⇒ methodNames.intA
            case LONG     ⇒ methodNames.lngA
            case DOUBLE   ⇒ methodNames.dblA
            case BOOLEAN  ⇒ methodNames.blnA
            case DURATION ⇒ methodNames.durA
          }

        case o: ObjSpec  ⇒ getClassName(o.key.simple)

        case l: ListSpec ⇒ _listMethodName(l.elemSpec, objCodeOpt)
      }
      val scalaType = getScalaType(spec)

      val definedListElemAccessors = objCodeOpt match {
        case None ⇒
          rootDefinedListElemAccessors

        case Some(objCode) ⇒
          if (atomicElemAccessDefinition.contains(elemAccessor))
            rootDefinedListElemAccessors
          else
            objCode.objectDefinedListElemAccessors
      }

      definedListElemAccessors += ((scalaType, elemAccessor))

      "$list" + elemAccessor
    }

    def _listMethodDefinition(elemJavaType: String, elemAccessor: String): (String, String) = {
      val elem = if (elemAccessor.startsWith("$list"))
        s"$elemAccessor(cv.asInstanceOf[com.typesafe.config.ConfigList])"
      else if (elemAccessor.startsWith("$"))
        s"$elemAccessor(cv)"
      else
        s"$elemAccessor(cv.asInstanceOf[com.typesafe.config.ConfigObject].toConfig)"

      val methodName = s"$$list$elemAccessor"
      val methodDef =
        s"""
           |private def $methodName(cl:com.typesafe.config.ConfigList): scala.collection.immutable.List[$elemJavaType] = {
           |  import scala.collection.JavaConversions._
           |  cl.map(cv => $elem).toList
           |}""".stripMargin.trim

      (methodName, methodDef)
    }

    // definition of methods used to access list's elements of basic type
    val atomicElemAccessDefinition: Map[String, String] = {
      import methodNames._
      Map(
        strA → s"""
                  |private def $strA(cv:com.typesafe.config.ConfigValue) =
                  |  java.lang.String.valueOf(cv.unwrapped())
                  |""".stripMargin.trim,

        intA → s"""
                  |private def $intA(cv:com.typesafe.config.ConfigValue): scala.Int = {
                  |  val u: Any = cv.unwrapped
                  |  if ((cv.valueType != com.typesafe.config.ConfigValueType.NUMBER)
                  |    || !u.isInstanceOf[Integer]) throw $expE(cv, "integer")
                  |  u.asInstanceOf[Integer]
                  |}""".stripMargin.trim,

        lngA → s"""
                  |private def $lngA(cv:com.typesafe.config.ConfigValue): scala.Long = {
                  |  val u: Any = cv.unwrapped
                  |  if ((cv.valueType != com.typesafe.config.ConfigValueType.NUMBER)
                  |    || !u.isInstanceOf[java.lang.Integer] && !u.isInstanceOf[java.lang.Long]) throw $expE(cv, "long")
                  |  u.asInstanceOf[java.lang.Number].longValue()
                  |}""".stripMargin.trim,

        dblA → s"""
                  |private def $dblA(cv:com.typesafe.config.ConfigValue): scala.Double = {
                  |  val u: Any = cv.unwrapped
                  |  if ((cv.valueType != com.typesafe.config.ConfigValueType.NUMBER)
                  |    || !u.isInstanceOf[java.lang.Number]) throw $expE(cv, "double")
                  |  u.asInstanceOf[java.lang.Number].doubleValue()
                  |}""".stripMargin.trim,

        blnA → s"""
                  |private def $blnA(cv:com.typesafe.config.ConfigValue): scala.Boolean = {
                  |  val u: Any = cv.unwrapped
                  |  if ((cv.valueType != com.typesafe.config.ConfigValueType.BOOLEAN)
                  |    || !u.isInstanceOf[java.lang.Boolean]) throw $expE(cv, "boolean")
                  |  u.asInstanceOf[java.lang.Boolean].booleanValue()
                  |}""".stripMargin.trim
      )
    }

    def _expE: String = {
      val expE = methodNames.expE
      s"""
         |private def $expE(cv:com.typesafe.config.ConfigValue, exp:java.lang.String) = {
         |  val u: Any = cv.unwrapped
         |  new java.lang.RuntimeException(cv.origin.lineNumber +
         |    ": expecting: " + exp + " got: " +
         |    (if (u.isInstanceOf[java.lang.String]) "\\"" + u + "\\"" else u))
         |}""".stripMargin.trim
    }

    def insertStaticAuxMethods(code:Code, isRoot: Boolean, indent: String, results: GenResult): Unit = {

      val methods = collection.mutable.HashMap[String, String]()

      code.objectDefinedListElemAccessors foreach { case (javaType, elemAccessor) ⇒
        val (methodName, methodDef) = _listMethodDefinition(javaType, elemAccessor)
        methods += methodName → methodDef
      }

      var insertExpE = false
      if (isRoot) {
        rootDefinedListElemAccessors foreach { case (javaType, elemAccessor) ⇒
          val (methodName, methodDef) = _listMethodDefinition(javaType, elemAccessor)
          methods += methodName → methodDef
        }

        rootDefinedListElemAccessors foreach { case (_, elemAccessor) ⇒
          atomicElemAccessDefinition.get(elemAccessor) foreach { methodDef ⇒
            methods += elemAccessor → methodDef
            if (elemAccessor != methodNames.strA) insertExpE = true
          }
        }
      }

      if (isRoot) {
        if (insertExpE) {
          methods += methodNames.expE → _expE
        }
      }

      if (methods.nonEmpty) {
        code.println("")
        methods.keys.toList.sorted foreach { methodName ⇒
          code.println(indent + methods(methodName).replaceAll("\n", "\n" + indent))
        }
      }
    }
  }

  object methodNames {
    val strA      = "$str"
    val intA      = "$int"
    val lngA      = "$lng"
    val dblA      = "$dbl"
    val blnA      = "$bln"
    val durA      = "$dur"
    val configAccess   = "_$config"
    val expE    = "$expE"
  }
}

object ScalaGenerator {
  import java.io.File

  import com.typesafe.config.ConfigFactory
  import tscfg.SpecBuilder

  def generate(filename: String, showOut: Boolean = false): GenResult = {
    val file = new File(filename)
    val src = io.Source.fromFile(file).mkString.trim

    if (showOut)
      println("src:\n  |" + src.replaceAll("\n", "\n  |"))

    val config = ConfigFactory.parseString(src).resolve()

    val className = "Scala" + {
      val noPath = filename.substring(filename.lastIndexOf('/') + 1)
      val noDef = noPath.replaceAll("""^def\.""", "")
      val symbol = noDef.substring(0, noDef.indexOf('.'))
      util.upperFirst(symbol) + "Cfg"
    }

    val objSpec = new SpecBuilder(Key(className)).fromConfig(config)
    if (showOut)
      println("\nobjSpec:\n  |" + objSpec.format().replaceAll("\n", "\n  |"))

    val genOpts = GenOpts("tscfg.example", className)

    val generator: Generator = new ScalaGenerator(genOpts)

    val results = generator.generate(objSpec)

    //println("\n" + results.code)

    val destFilename  = s"src/main/scala/tscfg/example/$className.scala"
    val destFile = new File(destFilename)
    val out = new PrintWriter(new FileWriter(destFile), true)
    out.println(results.code)
    results
  }

  // $COVERAGE-OFF$
  def main(args: Array[String]): Unit = {
    val filename = args(0)
    val results = generate(filename, showOut = true)
    println(
      s"""classNames: ${results.classNames}
         |fieldNames: ${results.fieldNames}
      """.stripMargin)
  }
  // $COVERAGE-ON$
}
