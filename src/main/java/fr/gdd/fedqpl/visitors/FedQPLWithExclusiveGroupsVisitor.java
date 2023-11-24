package fr.gdd.fedqpl.visitors;

import fr.gdd.fedqpl.operators.*;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.jena.graph.Node;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitor;
import org.apache.jena.sparql.algebra.op.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Create exclusive groups when they are close from each other
 */
public class FedQPLWithExclusiveGroupsVisitor extends ReturningOpVisitor<Op> {

    public static boolean SILENT = true;

    @Override
    public Op visit(Mu mu) {
        ImmutablePair<List<OpService>, List<Op>> p = divide(mu.getElements());
        Map<Node, List<OpService>> groups = group(p.getLeft());

        // builds new nested unions
        List<OpService> newGroups = new ArrayList<>();
        for (Node uri : groups.keySet()) {
            if (groups.get(uri).size() <= 1) {
                newGroups.add(groups.get(uri).getFirst());
            } else {
                Op left = groups.get(uri).getFirst().getSubOp();
                for (int i = 1; i < groups.get(uri).size(); ++i) {
                    Op right = groups.get(uri).get(i).getSubOp();
                    left = OpUnion.create(left, right);
                }
                newGroups.add(new OpService(uri, left, SILENT));
            }
        }

        List<Op> ops = p.getRight().stream().map(o ->
                ReturningOpVisitorRouter.visit(this, o)).toList();

        List<Op> muChildren = new ArrayList<>();
        muChildren.addAll(newGroups);
        muChildren.addAll(ops);

        return new Mu(muChildren);
    }

    @Override
    public Op visit(Mj mj) {
        ImmutablePair<List<OpService>, List<Op>> p = divide(mj.getElements());
        Map<Node, List<OpService>> groups = group(p.getLeft());

        // builds new joins
        List<OpService> newGroups = new ArrayList<>();
        for (Node uri : groups.keySet()) {
            if (groups.get(uri).size() <= 1) {
                newGroups.add(groups.get(uri).getFirst());
            } else {
                newGroups.add(new OpService(uri,
                        OpSequence.create().copy(groups.get(uri).stream().map(Op1::getSubOp).collect(Collectors.toList())),
                        SILENT));
            }
        }

        List<Op> ops = p.getRight().stream().map(o ->
                ReturningOpVisitorRouter.visit(this, o)).toList();

        List<Op> mjChildren = new ArrayList<>();
        mjChildren.addAll(newGroups);
        mjChildren.addAll(ops);

        return new Mj(mjChildren);
    }

    @Override
    public Op visit(OpService req) {
        return req; // do nothing
    }

    @Override
    public Op visit(OpConditional lj) {
        // check if left and right should be one big `Req` then merge
        // meaning they should have been simplified to the maximum beforehand.
        Op leftOp = lj.getLeft();
        Op rightOp = lj.getRight();

        leftOp = ReturningOpVisitorRouter.visit(new FedQPLSimplifyVisitor(), leftOp);
        rightOp = ReturningOpVisitorRouter.visit(new FedQPLSimplifyVisitor(), rightOp);

        if (rightOp instanceof OpService && leftOp instanceof OpService) {
            OpService left = (OpService) rightOp;
            OpService right = (OpService) leftOp;

            if (left.getService().equals(right.getService())) {
                return new OpService(left.getService(),
                        new OpConditional(left.getSubOp(), right.getSubOp()),
                        SILENT);
            }
        }
        // otherwise just run the thing inside each branch
        leftOp = ReturningOpVisitorRouter.visit(this, leftOp);
        rightOp = ReturningOpVisitorRouter.visit(this, rightOp);
        return new OpConditional(leftOp, rightOp);
    }

    @Override
    public Op visit(OpFilter filter) {
        return filter; // TODO filter push down in SERVICE
    }

    @Override
    public Op visit(OpSlice limit) {
        // TODO LIMIT push down, so it could be part of the query sent to endpoints.
        return limit;
    }

    @Override
    public Op visit(OpGroup orderBy) {
        // TODO ORDERBY push down, so it could be part of the query sent to endpoints.
        return orderBy;
    }

    @Override
    public Op visit(OpProject project) {
        // TODO PROJECT push down, so it could be part of the query sent to endpoints.
        return project;
    }

    @Override
    public Op visit(OpDistinct distinct) {
        // TODO PROJECT push down, so it could be part of the query sent to endpoints.
        return distinct;
    }

    /* ********************************************************************** */

    public static ImmutablePair<List<OpService>, List<Op>> divide(List<Op> children) {
        List<OpService> reqs = new ArrayList<>();
        List<Op> ops = new ArrayList<>();
        for (Op child : children) {
            if (child instanceof OpService) {
                reqs.add((OpService) child);
            } else {
                ops.add(child);
            }
        }
        return new ImmutablePair<>(reqs, ops);
    }

    // careful, the order might be different
    public static Map<Node, List<OpService>> group(List<OpService> toGroup) {
        Map<Node, List<OpService>> groups = new HashMap<>();
        for (OpService req: toGroup) {
            if (!groups.containsKey(req.getService()))
                groups.put(req.getService(), new ArrayList<>());
            groups.get(req.getService()).add(req);
        }
        return groups;
    }

}
