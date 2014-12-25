package models

import lib.{Primitives, Text}
import com.gilt.apidocgenerator.models._
import generator.{ScalaDatatype, ScalaPrimitive, GeneratorUtil, ScalaOperation, ScalaParameter, ScalaResource, ScalaService, ScalaUtil, CodeGenerator}

object Play2RouteGenerator extends CodeGenerator {

  override def invoke(form: InvocationForm): String = {
    new Play2RouteGenerator(form).invoke.getOrElse("")
  }
}


/**
 * Generates a Play routes file based on the service description
 * from api.json
 */
case class Play2RouteGenerator(form: InvocationForm) {

  private val GlobalPad = 5

  private val service = form.service
  private val scalaService = ScalaService(service)

  def invoke(): Option[String] = {
    val all = scalaService.resources.flatMap { case (modelName, resource) =>
      resource.operations.map { op =>
        Play2Route(scalaService, modelName, service.models(modelName), op, resource)
      }
    }
    if (all.isEmpty) {
      None
    } else {
      val maxVerbLength = all.map(_.verb.toString.length).toSeq.sorted.last
      val maxUrlLength = all.map(_.url.length).toSeq.sorted.last
      val (paramStart, pathStart) = all.partition(_.url.startsWith("/:"))

      Some(
        ApidocHeaders(form.userAgent).toRubyString() + "\n\n" +
        (pathStart ++ paramStart).map { r =>
          Seq(
            r.verb,
            " " * (maxVerbLength - r.verb.toString.length + GlobalPad),
            r.url,
            " " * (maxUrlLength - r.url.length + GlobalPad),
            r.method,
            "(",
            r.params.mkString(", "),
            ")",
            r.paramComments.map( c => "\n" + c ).getOrElse("")
          ).mkString("")
        }.mkString("\n")
      )
    }
  }
}

private[models] case class Play2Route(
  ssd: ScalaService,
  modelName: String,
  model: Model,
  op: ScalaOperation,
  resource: ScalaResource
) {

  val verb = op.method
  val url = op.path
  val params = parametersWithTypesAndDefaults(
    op.parameters.
      filter { param =>
        param.location match {
          case None => false
          case Some(ParameterLocation.Form) => false
          case Some(ParameterLocation.Query) => true
          case Some(ParameterLocation.Path) => true
        }
      }.
      filter { param =>
        param.datatype match {
          case ScalaDatatype.Option(_) | ScalaDatatype.Singleton(_) => true
          case ScalaDatatype.List(_) | ScalaDatatype.Map(_) => false
        }
      }
  )

  /**
    * Play does not have native support for providing a list as a
    * query parameter. Document these query parameters in the routes
    * file - but do not implement.
    */
  val paramComments: Option[String] = op.parameters.filter { param =>
    param.location match {
      case None => false
      case Some(ParameterLocation.Form) => false
      case Some(ParameterLocation.Query) => true
      case Some(ParameterLocation.Path) => true
    }
  }.filter { param =>
    param.datatype match {
      case ScalaDatatype.Option(_) | ScalaDatatype.Singleton(_) => false
      case ScalaDatatype.List(_) | ScalaDatatype.Map(_) => true
    }
  } match {
    case Nil => None
    case paramsToComment => {
      Some(
        Seq(
          s"# Additional parameters to ${op.method} ${op.path}",
          paramsToComment.map { p =>
            "#   - " + parameterWithType(ssd, p)
          }.mkString("\n")
        ).mkString("\n")
      )
    }
  }

  private val modelPlural = model.plural.getOrElse(Text.pluralize(modelName))
  val method = "%s.%s".format(
    "controllers." + lib.Text.underscoreAndDashToInitCap(modelPlural),
    GeneratorUtil.urlToMethodName(modelPlural, resource.path.getOrElse(""), op.method, url)
  )

  private def parametersWithTypesAndDefaults(params: Iterable[ScalaParameter]): Iterable[String] = {
    params.map { param =>
      Seq(
        Some(parameterWithType(ssd, param)),
        param.default.map( d =>
          param.datatype match {
            case ScalaDatatype.List(_) => {
              sys.error("Cannot set defaults for lists")
            }
            case ScalaDatatype.Map(_) => {
              sys.error("Cannot set defaults for maps")
            }
            case ScalaDatatype.Singleton(types) => {
              types match {
                case single :: Nil => {
                  "?= " + defaultForPrimitive(single, d)
                }
                case multiple => {
                  sys.error("TODO: UNION TYPE")
                }
              }
            }
            case ScalaDatatype.Option(types) => {
              types match {
                case single :: Nil => {
                  "?= Some(" + defaultForPrimitive(single, d) + ")"
                }
                case multiple => {
                  sys.error("TODO: UNION TYPE")
                }
              }
            }
          }
        )
      ).flatten.mkString(" ")
    }
  }

  private def defaultForPrimitive(
    primitive: ScalaPrimitive,
    value: String
  ): String = primitive match {
    case ScalaPrimitive.String | ScalaPrimitive.DateIso8601 | ScalaPrimitive.DateTimeIso8601 | ScalaPrimitive.Uuid => {
      ScalaUtil.wrapInQuotes(value)
    }
    case ScalaPrimitive.Integer | ScalaPrimitive.Double | ScalaPrimitive.Long | ScalaPrimitive.Boolean | ScalaPrimitive.Decimal => {
      value
    }
    case ScalaPrimitive.Object => {
      "play.api.libs.json.parse(%s)".format(ScalaUtil.wrapInQuotes(value))
    }
    case ScalaPrimitive.Enum(_) => {
      "%s(%s)".format(primitive.name, ScalaUtil.wrapInQuotes(value))
    }
    case ScalaPrimitive.Model(_) | ScalaPrimitive.Unit => {
      sys.error(s"Unsupported type[$primitive] for default values")
    }
  }

  private def parameterWithType(ssd: ScalaService, param: ScalaParameter): String = {
    s"${param.name}: ${param.datatype.name}"
  }

}
