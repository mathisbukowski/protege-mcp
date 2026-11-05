package dev.protegemcp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.InconsistentOntologyException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import uk.ac.manchester.cs.owlapi.modularity.ModuleType;
import uk.ac.manchester.cs.owlapi.modularity.SyntacticLocalityModuleExtractor;

/**
 * Black-box justification search: QuickXplain finds one minimal axiom set, and a hitting-set tree
 * finds more. Works on a private copy of the axioms, so it never touches the live ontology.
 */
final class Justifier {
    private final OWLReasonerFactory factory;
    private final OWLReasonerConfiguration config;
    private final OWLAxiom target;
    private final OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
    private final long deadline;
    private volatile OWLReasoner running;
    final List<Set<OWLAxiom>> found = Collections.synchronizedList(new ArrayList<>());
    volatile boolean complete;

    /** A null target explains inconsistency. */
    Justifier(OWLReasonerFactory factory, OWLReasonerConfiguration config, OWLAxiom target, long deadline) {
        this.factory = factory;
        this.config = config;
        this.target = target;
        this.deadline = deadline;
    }

    private static final class OutOfTime extends RuntimeException {
    }

    void interrupt() {
        OWLReasoner r = running;
        if (r != null) {
            r.interrupt();
        }
    }

    /** True if the axioms are inconsistent or entail the target. */
    boolean holds(Set<OWLAxiom> axioms) throws OWLOntologyCreationException {
        if (System.currentTimeMillis() > deadline) {
            throw new OutOfTime();
        }
        OWLOntology o = manager.createOntology(axioms);
        OWLReasoner r = factory.createNonBufferingReasoner(o, config);
        running = r;
        try {
            if (!r.isConsistent()) {
                return true;
            }
            return target != null && r.isEntailed(target);
        } catch (InconsistentOntologyException e) {
            return true;
        } finally {
            running = null;
            r.dispose();
            manager.removeOntology(o);
        }
    }

    /** Shrinks the search space. A STAR module keeps every justification of the target. */
    Set<OWLAxiom> module(Set<OWLAxiom> axioms) throws OWLOntologyCreationException {
        if (target == null) {
            return axioms;
        }
        OWLOntology o = manager.createOntology(axioms);
        try {
            Set<OWLEntity> sig = target.getSignature();
            return new SyntacticLocalityModuleExtractor(manager, o, ModuleType.STAR).extract(sig);
        } finally {
            manager.removeOntology(o);
        }
    }

    /** Fills `found` with up to `max` justifications. `complete` means none were missed. */
    void search(Set<OWLAxiom> universe, int max) throws OWLOntologyCreationException {
        Deque<Set<OWLAxiom>> queue = new ArrayDeque<>();
        Set<Set<OWLAxiom>> seen = new HashSet<>();
        queue.add(Collections.emptySet());
        try {
            while (!queue.isEmpty()) {
                if (found.size() >= max) {
                    return;
                }
                Set<OWLAxiom> removed = queue.poll();
                Set<OWLAxiom> reuse = null;
                synchronized (found) {
                    for (Set<OWLAxiom> j : found) {
                        if (Collections.disjoint(j, removed)) {
                            reuse = j;
                            break;
                        }
                    }
                }
                Set<OWLAxiom> just = reuse;
                if (just == null) {
                    Set<OWLAxiom> rest = new LinkedHashSet<>(universe);
                    rest.removeAll(removed);
                    if (!holds(rest)) {
                        continue;
                    }
                    just = minimise(Collections.emptySet(), false, new ArrayList<>(rest));
                    found.add(just);
                }
                for (OWLAxiom a : just) {
                    Set<OWLAxiom> next = new HashSet<>(removed);
                    next.add(a);
                    if (seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            complete = true;
        } catch (OutOfTime e) {
            // Partial results stay in `found`.
        }
    }

    /** QuickXplain. */
    private Set<OWLAxiom> minimise(Set<OWLAxiom> background, boolean changed, List<OWLAxiom> candidates)
            throws OWLOntologyCreationException {
        if (changed && holds(background)) {
            return Collections.emptySet();
        }
        if (candidates.size() == 1) {
            return new LinkedHashSet<>(candidates);
        }
        int half = candidates.size() / 2;
        List<OWLAxiom> left = candidates.subList(0, half);
        List<OWLAxiom> right = candidates.subList(half, candidates.size());
        Set<OWLAxiom> withLeft = new LinkedHashSet<>(background);
        withLeft.addAll(left);
        Set<OWLAxiom> fromRight = minimise(withLeft, true, right);
        Set<OWLAxiom> withRight = new LinkedHashSet<>(background);
        withRight.addAll(fromRight);
        Set<OWLAxiom> fromLeft = minimise(withRight, !fromRight.isEmpty(), left);
        Set<OWLAxiom> out = new LinkedHashSet<>(fromLeft);
        out.addAll(fromRight);
        return out;
    }
}
