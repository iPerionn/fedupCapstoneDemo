package fr.gdd.fedqpl;

import fr.gdd.fedqpl.operators.Mj;
import fr.gdd.fedqpl.operators.Mu;
import fr.gdd.fedqpl.visitors.ReturningOpVisitor;
import fr.gdd.fedqpl.visitors.ReturningOpVisitorRouter;
import org.apache.jena.graph.*;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpAsQueryMore;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.sse.writers.WriterExpr;
import org.apache.jena.sparql.util.ExprUtils;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.algebra.*;
import org.eclipse.rdf4j.query.parser.ParsedQuery;
import org.eclipse.rdf4j.query.parser.QueryParser;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import org.eclipse.rdf4j.query.parser.sparql.ast.ASTQueryContainer;
import org.eclipse.rdf4j.query.parser.sparql.ast.SyntaxTreeBuilder;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parsing the textual SPARQL query generated by FedUP may sometimes
 * take milliseconds, seconds, or even minutes, depending on the difficulty
 * of the federated query.
 *
 * To use FedUP with FedX, we need to remove this intermediate representation
 * when too difficult.
 */
public class FedQPL2FedX extends ReturningOpVisitor<TupleExpr> {

    private static Integer ANON_VARS = 0;
    private static boolean SILENT = true;

    private static String getAnonName() {
        ANON_VARS += 1;
        return "_anon_" + ANON_VARS;
    }

    /**
     * @param node The node to convert.
     * @return A FedX Var from a Jena Node.
     */
    private static Var getVar(Node node) {
        return switch (node) {
            case Node_Literal lit -> new Var(getAnonName(), Values.literal(lit.getLiteralLexicalForm()), true, true);
            case Node_URI uri -> new Var(getAnonName(), Values.iri(uri.getURI()), true, true);
            case Node_Variable var -> new Var(var.getName());
            default -> throw new UnsupportedOperationException(node.toString());
        };
    }

    @Override
    public TupleExpr visit(OpTriple triple) {
        Var s = getVar(triple.getTriple().getSubject());
        Var p = getVar(triple.getTriple().getPredicate());
        Var o = getVar(triple.getTriple().getObject());
        return new StatementPattern(s, p, o);
    }

    @Override
    public TupleExpr visit(OpBGP bgp) {
        return switch (bgp.getPattern().size()) {
            case 0 -> new EmptySet();
            case 1 -> ReturningOpVisitorRouter.visit(this, new OpTriple(bgp.getPattern().get(0)));
            default -> {
                // wrote as nested unions
                Iterator<Triple> ops = bgp.getPattern().iterator();
                TupleExpr left = ReturningOpVisitorRouter.visit(this, new OpTriple(ops.next()));
                while (ops.hasNext()) {
                    TupleExpr right = ReturningOpVisitorRouter.visit(this, new OpTriple(ops.next()));
                    left = new Join(left, right);
                }
                yield left;
            }
        };
    }

    @Override
    public TupleExpr visit(OpSequence sequence) {
        return switch (sequence.getElements().size()) {
            case 0 -> new EmptySet();
            case 1 -> ReturningOpVisitorRouter.visit(this, sequence.getElements().get(0));
            default -> {
                // wrote as nested unions
                Iterator<Op> ops = sequence.getElements().iterator();
                TupleExpr left = ReturningOpVisitorRouter.visit(this, ops.next());
                while (ops.hasNext()) {
                    TupleExpr right = ReturningOpVisitorRouter.visit(this, ops.next());
                    left = new Join(left, right);
                }
                yield left;
            }
        };
    }

    @Override
    public TupleExpr visit(OpService req) {
        Var serviceUri = new Var(getAnonName(), Values.iri(req.getService().getURI()), true, true);

        return new Service(serviceUri,
                ReturningOpVisitorRouter.visit(this, req.getSubOp()),
                OpAsQueryMore.asQuery(req.getSubOp()).toString(),
                Map.of(), // no prefix, already injected in URIs
                "", // baseURI
                SILENT);
    }

    @Override
    public TupleExpr visit(Mu mu) {
        return switch (mu.getElements().size()) {
            case 0 -> new EmptySet();
            case 1 -> ReturningOpVisitorRouter.visit(this, mu.getElements().iterator().next());
            default -> {
                // wrote as nested unions
                Iterator<Op> ops = mu.getElements().iterator();
                TupleExpr left = ReturningOpVisitorRouter.visit(this, ops.next());
                while (ops.hasNext()) {
                    TupleExpr right = ReturningOpVisitorRouter.visit(this, ops.next());
                    left = new Union(left, right);
                }
                yield left;
            }
        };
    }

    @Override
    public TupleExpr visit(Mj mj) {
        return switch (mj.getElements().size()) {
            case 0 -> new EmptySet();
            case 1 -> ReturningOpVisitorRouter.visit(this, mj.getElements().iterator().next());
            default -> {
                // wrote as nested unions
                Iterator<Op> ops = mj.getElements().iterator();
                TupleExpr left = ReturningOpVisitorRouter.visit(this, ops.next());
                while (ops.hasNext()) {
                    TupleExpr right = ReturningOpVisitorRouter.visit(this, ops.next());
                    left = new Join(left, right);
                }
                yield left;
            }
        };
    }

    @Override
    public TupleExpr visit(OpDistinct distinct) {
        return new Distinct(ReturningOpVisitorRouter.visit(this, distinct.getSubOp()));
    }

    @Override
    public TupleExpr visit(OpProject project) {
        return new Projection(ReturningOpVisitorRouter.visit(this,project.getSubOp()),
                new ProjectionElemList(project.getVars().stream().map(v -> new ProjectionElem(v.getVarName())).toList()));
    }

    @Override
    public TupleExpr visit(OpGroup groupBy) {
        if (!groupBy.getAggregators().isEmpty()) { // TODO
            throw new UnsupportedOperationException("Group by aggregators…");
        }
        return new Group(ReturningOpVisitorRouter.visit(this, groupBy.getSubOp()),
                groupBy.getGroupVars().getVars().stream().map(Node_Variable::toString).collect(Collectors.toList()));
    }

    @Override
    public TupleExpr visit(OpSlice slice) {
        return new Slice(ReturningOpVisitorRouter.visit(this, slice.getSubOp()),
                slice.getStart(),
                slice.getLength());
    }

    @Override
    public TupleExpr visit(OpFilter filter) {
        return new Filter(ReturningOpVisitorRouter.visit(this, filter.getSubOp()),
                getValueExpr(
                        String.join("&&",
                                filter.getExprs().getList().stream().map(ExprUtils::fmtSPARQL).toList())));
    }

    @Override
    public TupleExpr visit(OpOrder orderBy) {
        return new Order(ReturningOpVisitorRouter.visit(this, orderBy.getSubOp()),
                orderBy.getConditions().stream().map(sc->
                    new OrderElem(getValueExpr(ExprUtils.fmtSPARQL(sc.getExpression())))
                ).toList());
    }

    public static ValueExpr getValueExpr(String ExprAsSPARQL) {
        // TODO This is particularly ugly to get the filter condition in
        // TODO terms of FedX since nothing is set to parse the expression alone
        // TODO and get a `ValueExpr` to create the `Filter` operator…
        ParsedQuery parsedQuery = QueryParserUtil.parseQuery(QueryLanguage.SPARQL,
                "SELECT * WHERE {?s ?p ?o FILTER (" + ExprAsSPARQL + ")}",
                null);

        ValueExpr expr = null;
        try {
            parsedQuery.getTupleExpr().visit(new ValueExprGetterVisitor());
        } catch (ValueExprException e) {
            expr = e.expr;
            // ((AbstractQueryModelNode) expr).setParentNode(null);
            // TODO very very ugly. But we need to put parent to null otherwise it
            // TODO does not pass an assert in `setParentNode`. Could also disable runtime assertions as
            // TODO it probably should in release mode anyway.
            try {
                Field privateField = AbstractQueryModelNode.class.getDeclaredField("parent");
                privateField.setAccessible(true);
                privateField.set(expr, null);
            } catch (Exception exception) {
                throw new RuntimeException(exception.getMessage());
            }
        }
        return expr;
    }
}
