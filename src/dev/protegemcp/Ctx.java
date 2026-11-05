package dev.protegemcp;

import java.awt.Window;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParser;
import org.semanticweb.owlapi.functional.renderer.FunctionalSyntaxObjectRenderer;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.util.mansyntax.ManchesterOWLSyntaxParser;

/** Helpers bound to one Protégé window. All methods except forKit must run on the Swing thread. */
final class Ctx {
    final OWLEditorKit kit;
    final OWLModelManager mm;
    final OWLDataFactory df;

    private Ctx(OWLEditorKit kit) {
        this.kit = kit;
        this.mm = kit.getOWLModelManager();
        this.df = mm.getOWLDataFactory();
    }

    static Ctx forKit(Args a) throws ToolError {
        List<OWLEditorKit> kits = ProtegeMcpHook.kits();
        if (kits.isEmpty()) {
            throw new ToolError("No Protégé window is open.");
        }
        if (a.has("workspace")) {
            int i = a.integer("workspace", 0);
            if (i < 0 || i >= kits.size()) {
                throw new ToolError("No workspace " + i + ". Open workspaces: 0.." + (kits.size() - 1));
            }
            return new Ctx(kits.get(i));
        }
        for (OWLEditorKit k : kits) {
            Window w = SwingUtilities.getWindowAncestor(k.getOWLWorkspace());
            if (w != null && w.isActive()) {
                return new Ctx(k);
            }
        }
        return new Ctx(kits.get(kits.size() - 1));
    }

    OWLOntology active() {
        return mm.getActiveOntology();
    }

    /** Selects the ontology named by the `ontology` argument, or the active one. */
    OWLOntology target(Args a) throws ToolError {
        String want = a.opt("ontology", null);
        if (want == null) {
            return active();
        }
        for (OWLOntology o : mm.getOntologies()) {
            if (want.equals(iriOf(o)) || want.equals(mm.getRendering(o))) {
                return o;
            }
        }
        throw new ToolError("No loaded ontology matches " + want + ". See `status`.");
    }

    OWLOntology mutableTarget(Args a) throws ToolError {
        OWLOntology o = target(a);
        if (!mm.isMutable(o)) {
            throw new ToolError("Ontology is read-only: " + iriOf(o));
        }
        return o;
    }

    static String iriOf(OWLOntology o) {
        return o.getOntologyID().getOntologyIRI().isPresent()
                ? o.getOntologyID().getOntologyIRI().get().toString()
                : o.getOntologyID().toString();
    }

    /** Prefixes, matching the ones FunctionalSyntaxObjectRenderer writes for the same ontology. */
    Map<String, String> prefixes(OWLOntology o) {
        Map<String, String> p = new TreeMap<>();
        p.put("owl:", "http://www.w3.org/2002/07/owl#");
        p.put("rdf:", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        p.put("rdfs:", "http://www.w3.org/2000/01/rdf-schema#");
        p.put("xsd:", "http://www.w3.org/2001/XMLSchema#");
        p.put("xml:", "http://www.w3.org/XML/1998/namespace");
        p.put("skos:", "http://www.w3.org/2004/02/skos/core#");
        OWLDocumentFormat f = o.getOWLOntologyManager().getOntologyFormat(o);
        if (f instanceof PrefixDocumentFormat) {
            p.putAll(((PrefixDocumentFormat) f).getPrefixName2PrefixMap());
        }
        if (!p.containsKey(":")) {
            String iri = o.getOntologyID().getOntologyIRI().isPresent()
                    ? o.getOntologyID().getOntologyIRI().get().toString()
                    : "urn:protege-mcp:anonymous#";
            p.put(":", iri.endsWith("/") || iri.endsWith("#") ? iri : iri + "#");
        }
        return p;
    }

    /** Expands `<iri>`, `prefix:local` or an absolute IRI. Returns null for a plain name. */
    IRI expand(String ref) {
        if (ref.startsWith("<") && ref.endsWith(">")) {
            return IRI.create(ref.substring(1, ref.length() - 1));
        }
        int colon = ref.indexOf(':');
        if (colon < 0 || ref.contains(" ")) {
            return null;
        }
        String ns = prefixes(active()).get(ref.substring(0, colon + 1));
        if (ns != null) {
            return IRI.create(ns + ref.substring(colon + 1));
        }
        return ref.contains("://") || ref.startsWith("urn:") ? IRI.create(ref) : null;
    }

    OWLEntity resolve(String ref) throws ToolError {
        return resolve(ref, null);
    }

    /** Resolves an IRI, prefixed name, short name or label. Fails if the reference is ambiguous. */
    OWLEntity resolve(String ref, EntityType<?> kind) throws ToolError {
        OWLOntology o = active();
        Set<OWLEntity> hits = new LinkedHashSet<>();
        IRI iri = expand(ref);
        if (iri != null) {
            hits.addAll(o.getEntitiesInSignature(iri, Imports.INCLUDED));
            if (kind != null && hits.stream().noneMatch(e -> e.getEntityType().equals(kind))) {
                OWLEntity e = df.getOWLEntity(kind, iri);
                if (e.isBuiltIn()) {
                    return e;
                }
            }
            if (hits.isEmpty() && iri.equals(df.getOWLThing().getIRI())) {
                return df.getOWLThing();
            }
        } else {
            hits.addAll(mm.getOWLEntityFinder().getOWLEntities(ref));
            if (hits.isEmpty()) {
                scanNames(o, ref, hits);
            }
        }
        if (kind != null) {
            hits = hits.stream().filter(e -> e.getEntityType().equals(kind))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        if (hits.isEmpty()) {
            String want = kind == null ? "entity" : kind.getPrintName();
            throw new ToolError("No " + want + " matches '" + ref + "'. Try search_entities.");
        }
        if (hits.size() > 1) {
            throw new ToolError("'" + ref + "' is ambiguous: "
                    + hits.stream().map(e -> e.getEntityType().getPrintName() + " " + e.getIRI())
                            .collect(Collectors.joining(", ")));
        }
        return hits.iterator().next();
    }

    private void scanNames(OWLOntology o, String ref, Set<OWLEntity> hits) {
        String want = ref.toLowerCase(Locale.ROOT);
        for (OWLEntity e : o.getSignature(Imports.INCLUDED)) {
            String shortName = e.getIRI().getRemainder().or("");
            if (shortName.equalsIgnoreCase(ref)) {
                hits.add(e);
                continue;
            }
            for (String label : labels(e)) {
                if (label.toLowerCase(Locale.ROOT).equals(want)) {
                    hits.add(e);
                }
            }
        }
    }

    List<String> labels(OWLEntity e) {
        List<String> out = new ArrayList<>();
        for (OWLAnnotationAssertionAxiom ax : annotations(e)) {
            String p = ax.getProperty().getIRI().toString();
            if (p.endsWith("#label") || p.endsWith("#prefLabel") || p.endsWith("#altLabel")) {
                out.add(valueText(ax.getValue()));
            }
        }
        return out;
    }

    Set<OWLAnnotationAssertionAxiom> annotations(OWLEntity e) {
        Set<OWLAnnotationAssertionAxiom> out = new HashSet<>();
        for (OWLOntology o : active().getImportsClosure()) {
            out.addAll(o.getAnnotationAssertionAxioms(e.getIRI()));
        }
        return out;
    }

    static String valueText(OWLAnnotationValue v) {
        return v instanceof OWLLiteral ? ((OWLLiteral) v).getLiteral() : v.toString();
    }

    Map<String, Object> entity(OWLEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name(e));
        m.put("type", e.getEntityType().getName());
        m.put("iri", e.getIRI().toString());
        return m;
    }

    /** The GUI rendering, without the ':' Protégé shows for names in the default namespace. */
    String name(OWLObject o) {
        String r = mm.getRendering(o);
        return r.startsWith(":") ? r.substring(1) : r;
    }

    /** Short display names, sorted, without owl:Nothing. */
    List<String> names(Collection<? extends OWLObject> objects) {
        return objects.stream().filter(o -> !o.isBottomEntity()).map(this::name).sorted().collect(Collectors.toList());
    }

    /** Axioms as text. `functional` output can be passed back to remove_axioms unchanged. */
    List<String> render(Collection<? extends OWLAxiom> axioms, String mode, OWLOntology o) {
        List<String> out = new ArrayList<>();
        if ("manchester".equals(mode)) {
            for (OWLAxiom ax : axioms) {
                out.add(mm.getRendering(ax));
            }
        } else {
            StringWriter w = new StringWriter();
            FunctionalSyntaxObjectRenderer r = new FunctionalSyntaxObjectRenderer(o, w);
            for (OWLAxiom ax : axioms) {
                w.getBuffer().setLength(0);
                ax.accept(r);
                out.add(w.toString());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /** Lets Manchester syntax use the same references as every other tool. */
    private final class Names implements OWLEntityChecker {
        private <T extends OWLEntity> T find(String token, EntityType<T> kind) {
            String ref = token;
            if (ref.length() > 1 && ref.startsWith("'") && ref.endsWith("'")) {
                ref = ref.substring(1, ref.length() - 1);
            }
            if (kind == EntityType.CLASS && (ref.equals("Thing") || ref.equals("Nothing"))) {
                ref = "owl:" + ref;
            }
            try {
                @SuppressWarnings("unchecked")
                T hit = (T) resolve(ref, kind);
                return hit;
            } catch (ToolError e) {
                return null;
            }
        }

        @Override
        public OWLClass getOWLClass(String name) {
            return find(name, EntityType.CLASS);
        }

        @Override
        public OWLObjectProperty getOWLObjectProperty(String name) {
            return find(name, EntityType.OBJECT_PROPERTY);
        }

        @Override
        public OWLDataProperty getOWLDataProperty(String name) {
            return find(name, EntityType.DATA_PROPERTY);
        }

        @Override
        public OWLNamedIndividual getOWLIndividual(String name) {
            return find(name, EntityType.NAMED_INDIVIDUAL);
        }

        @Override
        public OWLDatatype getOWLDatatype(String name) {
            return find(name, EntityType.DATATYPE);
        }

        @Override
        public OWLAnnotationProperty getOWLAnnotationProperty(String name) {
            return find(name, EntityType.ANNOTATION_PROPERTY);
        }
    }

    private ManchesterOWLSyntaxParser manchester(String text) {
        ManchesterOWLSyntaxParser p = OWLManager.createManchesterParser();
        p.setDefaultOntology(active());
        p.setOWLEntityChecker(new Names());
        p.setStringToParse(text);
        return p;
    }

    OWLClassExpression parseClassExpression(String text) throws ToolError {
        try {
            return manchester(text).parseClassExpression();
        } catch (RuntimeException e) {
            throw new ToolError("Bad Manchester class expression: " + firstLine(e));
        }
    }

    /** Parses axioms in `functional` or `manchester` syntax. */
    Set<OWLAxiom> parseAxioms(List<String> texts, String syntax, OWLOntology context) throws ToolError {
        Set<OWLAxiom> out = new LinkedHashSet<>();
        if ("manchester".equals(syntax)) {
            for (String t : texts) {
                try {
                    out.add(manchester(t).parseAxiom());
                } catch (RuntimeException e) {
                    throw new ToolError("Bad Manchester axiom '" + t + "': " + firstLine(e));
                }
            }
            return out;
        }
        if (!"functional".equals(syntax)) {
            throw new ToolError("syntax must be functional or manchester");
        }
        try {
            return parseFunctional(texts, context);
        } catch (Exception whole) {
            for (String t : texts) {
                try {
                    parseFunctional(List.of(t), context);
                } catch (Exception e) {
                    throw new ToolError("Bad functional axiom '" + t + "': " + firstLine(e));
                }
            }
            throw new ToolError("Bad functional axioms: " + firstLine(whole));
        }
    }

    private Set<OWLAxiom> parseFunctional(List<String> texts, OWLOntology context) throws Exception {
        StringBuilder doc = new StringBuilder();
        prefixes(context).forEach((k, v) -> doc.append("Prefix(").append(k).append("=<").append(v).append(">)\n"));
        doc.append("Ontology(\n");
        for (String t : texts) {
            doc.append(t).append('\n');
        }
        doc.append(")\n");
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology tmp = m.createOntology();
        new OWLFunctionalSyntaxOWLParser().parse(new StringDocumentSource(doc.toString()), tmp,
                new OWLOntologyLoaderConfiguration());
        return new LinkedHashSet<>(tmp.getAxioms());
    }

    private static String firstLine(Exception e) {
        String m = String.valueOf(e.getMessage());
        int n = m.indexOf('\n');
        return n < 0 ? m : m.substring(0, n);
    }

    /** Logical axioms of the imports closure: the input for snapshot reasoning. */
    static Set<OWLAxiom> logicalAxioms(OWLOntology o) {
        Set<OWLAxiom> out = new HashSet<>();
        for (OWLOntology x : o.getImportsClosure()) {
            out.addAll(x.getLogicalAxioms());
        }
        return out;
    }

    /** The named reasoner, else the current one, else HermiT, else any installed one. */
    ProtegeOWLReasonerInfo pickReasoner(String wanted) throws ToolError {
        OWLReasonerManager rm = mm.getOWLReasonerManager();
        if (wanted != null) {
            String w = wanted.toLowerCase(Locale.ROOT);
            for (ProtegeOWLReasonerInfo i : rm.getInstalledReasonerFactories()) {
                if (i.getReasonerId().equalsIgnoreCase(wanted) || i.getReasonerName().toLowerCase(Locale.ROOT).contains(w)) {
                    return i;
                }
            }
            throw new ToolError("No reasoner matches '" + wanted + "'. See `status`.");
        }
        ProtegeOWLReasonerInfo current = rm.getCurrentReasonerFactory();
        if (!isNoOp(current)) {
            return current;
        }
        ProtegeOWLReasonerInfo fallback = null;
        for (ProtegeOWLReasonerInfo i : rm.getInstalledReasonerFactories()) {
            if (isNoOp(i)) {
                continue;
            }
            if (i.getReasonerName().toLowerCase(Locale.ROOT).contains("hermit")) {
                return i;
            }
            fallback = i;
        }
        if (fallback == null) {
            throw new ToolError("No reasoner is installed in Protégé.");
        }
        return fallback;
    }

    static boolean isNoOp(ProtegeOWLReasonerInfo i) {
        return i.getClass().getSimpleName().contains("NoOp");
    }
}
