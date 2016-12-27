package scraper.fastparser

import fastparse.all._

// SQL06 section 5.2
object SeparatorParser {
  private val EoF = '\u001a'

  lazy val whitespace: P0 =
    CharPred { ch => ch <= ' ' && ch != EoF } opaque "whitespace"

  private val comment: P0 = (
    "--" ~ (!"\n").rep
    | "/*" ~ (!"*").rep ~ "*/"
  ) opaque "comment"

  lazy val separator: P0 =
    comment | whitespace opaque "separator"
}
