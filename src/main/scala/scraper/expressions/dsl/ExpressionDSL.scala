package scraper.expressions.dsl

import scraper.expressions.{Alias, Cast, Expression}
import scraper.types.DataType

trait ExpressionDSL extends ArithmeticExpressionDSL with ComparisonDSL { this: Expression =>
  def as(alias: String): Alias = Alias(alias, this)

  def as(alias: Symbol): Alias = Alias(alias.name, this)

  def cast(dataType: DataType): Cast = Cast(this, dataType)
}
