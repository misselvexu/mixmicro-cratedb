/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.planner.optimizer.rule;

import static io.crate.planner.optimizer.matcher.Pattern.typeOf;
import static io.crate.planner.optimizer.matcher.Patterns.source;
import static io.crate.planner.optimizer.rule.FilterOnJoinsUtil.getNewSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import io.crate.analyze.relations.QuerySplitter;
import io.crate.common.annotations.VisibleForTesting;
import io.crate.expression.operator.AndOperator;
import io.crate.expression.symbol.Function;
import io.crate.expression.symbol.ScopedSymbol;
import io.crate.expression.symbol.Symbol;
import io.crate.expression.symbol.SymbolVisitor;
import io.crate.metadata.NodeContext;
import io.crate.metadata.Reference;
import io.crate.metadata.RelationName;
import io.crate.metadata.TransactionContext;
import io.crate.planner.node.dql.join.JoinType;
import io.crate.planner.operators.HashJoin;
import io.crate.planner.operators.Eval;
import io.crate.planner.operators.LogicalPlan;
import io.crate.planner.operators.NestedLoopJoin;
import io.crate.planner.optimizer.Rule;
import io.crate.planner.optimizer.matcher.Capture;
import io.crate.planner.optimizer.matcher.Captures;
import io.crate.planner.optimizer.matcher.Pattern;
import io.crate.statistics.TableStats;

public class RewriteNestedLoopJoinWithConstantConditions implements Rule<Eval> {

    private final Capture<NestedLoopJoin> nlCapture;
    private final Pattern<Eval> pattern;

    public RewriteNestedLoopJoinWithConstantConditions() {
        this.nlCapture = new Capture<>();
        this.pattern = typeOf(Eval.class)
            .with(source(),
                  typeOf(NestedLoopJoin.class)
                      .capturedAs(nlCapture)
                      .with(nl -> nl.joinType() == JoinType.INNER &&
                                  !nl.isJoinConditionOptimised() &&
                                  nl.joinCondition() != null)
            );
    }

    @Override
    public Pattern<Eval> pattern() {
        return pattern;
    }

    @Override
    public LogicalPlan apply(Eval plan,
                             Captures captures,
                             TableStats tableStats,
                             TransactionContext txnCtx,
                             NodeContext nodeCtx) {
        var nl = captures.get(nlCapture);
        var conditions = nl.joinCondition();
        var allConditions = QuerySplitter.split(conditions);
        var constantConditions = new HashMap<Set<RelationName>, Symbol>(allConditions.size());
        var nonConstantConditions = new HashSet<Symbol>(allConditions.size());
        for (var condition : allConditions.entrySet()) {
            if (numberOfRelationsUsed(condition.getValue()) <= 1) {
                constantConditions.put(condition.getKey(), condition.getValue());
            } else {
                nonConstantConditions.add(condition.getValue());
            }
        }
        if (constantConditions.isEmpty()) {
            // Nothing to optimize, just mark nestedLoopJoin to skip the rule the next time
            return Eval.create(
                new NestedLoopJoin(
                    nl.lhs(),
                    nl.rhs(),
                    nl.joinType(),
                    nl.joinCondition(),
                    nl.isFiltered(),
                    nl.topMostLeftRelation(),
                    nl.orderByWasPushedDown(),
                    nl.isRewriteFilterOnOuterJoinToInnerJoinDone(),
                    true // Mark joinConditionOptimised = true
                ),
                plan.outputs()
            );
        } else {
            // Push constant join condition down to source
            var lhs = nl.lhs();
            var rhs = nl.rhs();
            var queryForLhs = constantConditions.remove(lhs.getRelationNames());
            var queryForRhs = constantConditions.remove(rhs.getRelationNames());
            var newLhs = getNewSource(queryForLhs, lhs);
            var newRhs = getNewSource(queryForRhs, rhs);
            return Eval.create(
                new HashJoin(
                    newLhs,
                    newRhs,
                    AndOperator.join(nonConstantConditions),
                    nl.topMostLeftRelation()
                ),
                plan.outputs()
            );
        }
    }

    @VisibleForTesting
    static int numberOfRelationsUsed(Symbol joinCondition) {
        var usedRelationsInsideEqOperatorArgument = new HashSet<RelationName>();
        joinCondition.accept(VISITOR, usedRelationsInsideEqOperatorArgument);
        return usedRelationsInsideEqOperatorArgument.size();
    }

    private static final Visitor VISITOR = new Visitor();

    private static class Visitor extends SymbolVisitor<Set<RelationName>, Void> {

        @Override
        public Void visitFunction(Function function, Set<RelationName> context) {
            for (Symbol arg : function.arguments()) {
                arg.accept(this, context);
            }
            return null;
        }

        @Override
        public Void visitField(ScopedSymbol field, Set<RelationName> context) {
            context.add(field.relation());
            return null;
        }

        @Override
        public Void visitReference(Reference ref, Set<RelationName> context) {
            context.add(ref.ident().tableIdent());
            return null;
        }
    }
}
