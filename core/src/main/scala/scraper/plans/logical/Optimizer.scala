package scraper.plans.logical

import scraper.expressions
import scraper.expressions.Literal.{False, True}
import scraper.expressions.Predicate.{splitConjunction, toCNF}
import scraper.expressions._
import scraper.expressions.dsl._
import scraper.expressions.functions._
import scraper.plans.logical
import scraper.plans.logical.Optimizer._
import scraper.plans.logical.dsl._
import scraper.plans.logical.patterns.PhysicalOperation.inlineAliases
import scraper.trees.RulesExecutor.FixedPoint
import scraper.trees.{Rule, RulesExecutor}

class Optimizer extends RulesExecutor[LogicalPlan] {
  override def batches: Seq[RuleBatch] = Seq(
    RuleBatch("Optimizations", FixedPoint.Unlimited, Seq(
      FoldConstants,
      FoldLogicalPredicates,

      CNFConversion,
      EliminateCommonPredicates,

      ReduceAliases,
      ReduceCasts,
      MergeFilters,
      ReduceLimits,
      ReduceNegations,
      MergeProjects,
      EliminateSubqueries,

      PushFiltersThroughProjects,
      PushFiltersThroughJoins,
      PushProjectsThroughLimits,
      PushLimitsThroughUnions
    ))
  )

  override def apply(tree: LogicalPlan): LogicalPlan = {
    assert(
      tree.isResolved,
      s"""Logical query plan not resolved yet:
         |
         |${tree.prettyTree}
         |""".stripMargin
    )

    logDebug(
      s"""Optimizing logical query plan:
         |
         |${tree.prettyTree}
         |""".stripMargin
    )

    super.apply(tree)
  }
}

object Optimizer {
  /**
   * This rule finds all foldable expressions and evaluate them to literals.
   */
  object FoldConstants extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformAllExpressions {
      case e if e.isFoldable => lit(e.evaluated)
    }
  }

  /**
   * This rule simplifies logical predicates containing `TRUE` and/or `FALSE`.
   */
  object FoldLogicalPredicates extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformAllExpressions {
      case True || _          => True
      case _ || True          => True

      case False && _         => False
      case _ && False         => False

      case a && b if a same b => a
      case a || b if a same b => a

      case If(True, yes, _)   => yes
      case If(False, _, no)   => no
    }
  }

  /**
   * This rule eliminates unnecessary [[expressions.Not Not]] operators.
   */
  object ReduceNegations extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformAllExpressions {
      case !(!(child))           => child
      case !(lhs =:= rhs)        => lhs =/= rhs
      case !(lhs =/= rhs)        => lhs =:= rhs

      case !(lhs > rhs)          => lhs <= rhs
      case !(lhs >= rhs)         => lhs < rhs
      case !(lhs < rhs)          => lhs >= rhs
      case !(lhs <= rhs)         => lhs > rhs

      case If(!(c), t, f)        => If(c, f, t)

      case a && !(b) if a same b => False
      case a || !(b) if a same b => True

      case !(IsNull(child))      => IsNotNull(child)
      case !(IsNotNull(child))   => IsNull(child)
    }
  }

  /**
   * This rule eliminates unnecessary casts.  For example, implicit casts introduced by the
   * [[Analyzer]] may produce redundant casts.
   */
  object ReduceCasts extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformAllExpressions {
      case e Cast t if e.dataType == t => e
      case e Cast _ Cast t             => e cast t
    }
  }

  /**
   * This rule reduces adjacent projects.  Aliases are also inlined/substituted when possible.
   */
  object MergeProjects extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan Project projectList if projectList == plan.output =>
        plan

      case plan Project innerList Project outerList =>
        plan select (outerList map (inlineAliases(innerList, _)))
    }
  }

  /**
   * This rule reduces adjacent aliases.
   */
  object ReduceAliases extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformAllExpressions {
      case outer @ Alias(inner: Alias, _, _) => outer.copy(child = inner.child)
    }
  }

  /**
   * This rule converts a predicate to CNF (Conjunctive Normal Form).
   *
   * Since we don't support existential/universal quantifiers or implications, this rule simply
   * pushes negations inwards by applying De Morgan's law and distributes [[expressions.Or Or]]s
   * inwards over [[expressions.And And]]s.
   *
   * @see https://en.wikipedia.org/wiki/Conjunctive_normal_form
   */
  object CNFConversion extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan Filter condition => plan filter toCNF(condition)
    }
  }

  object EliminateCommonPredicates extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformAllExpressions {
      case lhs && rhs if lhs == rhs            => lhs
      case lhs || rhs if lhs == rhs            => lhs
      case If(condition, yes, no) if yes == no => Coalesce(condition, yes)
    }
  }

  /**
   * This rule combines adjacent [[logical.Filter Filter]]s into a single [[logical.Filter Filter]].
   */
  object MergeFilters extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan Filter inner Filter outer => plan filter (inner && outer)
    }
  }

  object FoldConstantFilters extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan Filter True  => plan
      case plan Filter False => LocalRelation(Nil, plan.output)
    }
  }

  /**
   * This rule pushes [[logical.Filter Filter]]s beneath [[logical.Project Project]]s.
   */
  object PushFiltersThroughProjects extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan Project projectList Filter condition if projectList forall (_.isPure) =>
        val substitutedCondition = inlineAliases(projectList, condition)
        plan filter substitutedCondition select projectList
    }
  }

  /**
   * This rule pushes [[logical.Filter Filter]]s beneath [[logical.Join Join]]s whenever possible.
   */
  object PushFiltersThroughJoins extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case (join @ Join(left, right, Inner, joinCondition)) Filter filterCondition =>
        // Finds predicates that only reference attribute(s) of the left branch.  The filter
        // condition predicate is turned into CNF first so that we can push as many predicates as
        // possible.
        val (leftPredicates, rest) = splitConjunction(toCNF(filterCondition)) partition {
          _.referenceIDs subsetOf left.outputIDSet
        }

        // Finds predicates that only reference attribute(s) of the right branch and predicates
        // that reference attributes of both branches.
        val (rightPredicates, commonPredicates) = rest partition {
          _.referenceIDs subsetOf right.outputIDSet
        }

        def applyPredicates(predicates: Seq[Expression], plan: LogicalPlan): LogicalPlan =
          predicates reduceOption (_ && _) map plan.filter getOrElse plan

        join.copy(
          left = applyPredicates(leftPredicates, left),
          right = applyPredicates(rightPredicates, right),
          condition = (joinCondition ++ commonPredicates) reduceOption (_ && _)
        )
    }
  }

  object PushProjectsThroughLimits extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan Limit n Project projectList =>
        plan select projectList limit n
    }
  }

  object ReduceLimits extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case plan Limit n Limit m => Limit(plan, If(n < m, n, m))
    }
  }

  object PushLimitsThroughUnions extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case (left Union right) Limit n =>
        left limit n union (right limit n) limit n
    }
  }

  /**
   * This rule eliminates all [[scraper.plans.logical.Subquery Subquery]] operators, since they are
   * only used to provide scoping information during analysis phase.
   */
  object EliminateSubqueries extends Rule[LogicalPlan] {
    override def apply(tree: LogicalPlan): LogicalPlan = tree transformDown {
      case child Subquery _ => child
    } transformAllExpressions {
      case ref: AttributeRef => ref.copy(qualifier = None)
    }
  }
}