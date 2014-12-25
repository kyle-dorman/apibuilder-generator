package generator

import lib.Text

case class ScalaEnums(
  name: String,
  enum: ScalaEnum
) {

  def build(): String = {
    import lib.Text._
    Seq(
      enum.description.map { desc => ScalaUtil.textToComment(desc) + "\n" }.getOrElse("") +
        s"sealed trait ${name}",
      s"object ${name} {",
      buildValues().indent(2),
      s"}"
    ).mkString("\n\n")
  }

  /**
    * Returns the implicits for json serialization.
    */
  def buildJson(prefix: String): String = {
    Seq(
      s"implicit val jsonReads${prefix}Enum_$name = __.read[String].map(${name}.apply)",
      s"implicit val jsonWrites${prefix}Enum_$name = new Writes[$name] {",
      s"  def writes(x: $name) = JsString(x.toString)",
      "}"
    ).mkString("\n")
  }

  private def buildValues(): String = {
    enum.values.map { value => 
      Seq(
        value.description.map { desc => ScalaUtil.textToComment(desc) },
        Some(s"""case object ${value.name} extends ${name} { override def toString = "${value.originalName}" }""")
      ).flatten.mkString("\n")
    }.mkString("\n") + "\n" +
    s"""
/**
 * UNDEFINED captures values that are sent either in error or
 * that were added by the server after this library was
 * generated. We want to make it easy and obvious for users of
 * this library to handle this case gracefully.
 *
 * We use all CAPS for the variable name to avoid collisions
 * with the camel cased values above.
 */
case class UNDEFINED(override val toString: String) extends $name

/**
 * all returns a list of all the valid, known values. We use
 * lower case to avoid collisions with the camel cased values
 * above.
 */
""" +
    s"val all = Seq(" + enum.values.map(_.name).mkString(", ") + ")\n\n" +
    s"private[this]\n" +
    s"val byName = all.map(x => x.toString -> x).toMap\n\n" +
    s"def apply(value: String): $name = fromString(value).getOrElse(UNDEFINED(value))\n\n" +
    s"def fromString(value: String): scala.Option[$name] = byName.get(value)\n\n"
  }

}