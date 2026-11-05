package dev.protegemcp;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.protege.editor.core.ProtegeManager;
import org.protege.editor.core.editorkit.EditorKitFactoryPlugin;
import org.protege.editor.owl.OWLEditorKit;
import org.protege.editor.owl.model.OWLModelManager;
import org.protege.editor.owl.model.entity.OWLEntityCreationSet;
import org.protege.editor.owl.model.hierarchy.OWLObjectHierarchyProvider;
import org.protege.editor.owl.model.history.HistoryManager;
import org.protege.editor.owl.model.inference.OWLReasonerManager;
import org.protege.editor.owl.model.inference.ProtegeOWLReasonerInfo;
import org.protege.editor.owl.model.inference.ReasonerStatus;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.io.StringDocumentTarget;
import org.semanticweb.owlapi.model.AddAxiom;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataRange;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.RemoveAxiom;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.profiles.OWLProfileReport;
import org.semanticweb.owlapi.profiles.OWLProfileViolation;
import org.semanticweb.owlapi.profiles.Profiles;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.NullReasonerProgressMonitor;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerConfiguration;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.util.DLExpressivityChecker;
import org.semanticweb.owlapi.util.OWLEntityRemover;
import org.semanticweb.owlapi.util.OWLEntityRenamer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The MCP tool catalogue. */
final class Tools {
    private static final Logger log = LoggerFactory.getLogger(Tools.class);

    private interface Handler {
        Object call(Args a) throws Exception;
    }

    private interface ModelHandler {
        Object call(Ctx c, Args a) throws Exception;
    }

    private static final class Tool {
        Map<String, Object> spec;
        Handler handler;
    }

    /** Builds a JSON schema for a tool's input. */
    private static final class Schema {
        private final Map<String, Object> props = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        private Schema add(String name, String type, String desc, boolean req) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type);
            p.put("description", desc);
            props.put(name, p);
            if (req) {
                required.add(name);
            }
            return this;
        }

        Schema str(String name, String desc) {
            return add(name, "string", desc, false);
        }

        Schema needStr(String name, String desc) {
            return add(name, "string", desc, true);
        }

        Schema bool(String name, String desc) {
            return add(name, "boolean", desc, false);
        }

        Schema integer(String name, String desc) {
            return add(name, "integer", desc, false);
        }

        private Schema list(String name, String desc, boolean req, Map<String, Object> items) {
            add(name, "array", desc, req);
            ((Map<String, Object>) cast(props.get(name))).put("items", items);
            return this;
        }

        Schema strs(String name, String desc) {
            return list(name, desc, false, Collections.singletonMap("type", "string"));
        }

        Schema needStrs(String name, String desc) {
            return list(name, desc, true, Collections.singletonMap("type", "string"));
        }

        Schema needObjects(String name, String desc) {
            return list(name, desc, true, Collections.singletonMap("type", "object"));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> cast(Object o) {
            return (Map<String, Object>) o;
        }

        Map<String, Object> build() {
            integer("workspace", "Protégé window index from `status`. Default: the focused window.");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "object");
            m.put("properties", props);
            if (!required.isEmpty()) {
                m.put("required", required);
            }
            return m;
        }
    }

    private static final String ONT = "Ontology IRI. Default: the active ontology.";
    private static final String AXIOMS =
            "Axioms in OWL functional syntax, such as SubClassOf(:A :B). ':' is the default prefix.";
    private static final String SYNTAX = "functional (default, all axioms) or manchester (class axioms only).";
    private static final String RENDER = "manchester or functional. Functional output can feed remove_axioms.";
    private static final String REASONER = "Reasoner name or id. Default: the current one, else HermiT.";

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    Tools() {
        ro("status", "List open windows, loaded ontologies, the selected entity, reasoners and undo state. Call this first.",
                new Schema(), this::status);
        ro("ontology_info", "Show axiom and entity counts, DL expressivity, imports, prefixes and ontology annotations.",
                new Schema().str("ontology", ONT), this::ontologyInfo);
        ro("search_entities", "Find entities by IRI, short name or label. Best matches come first.",
                new Schema().needStr("query", "Substring, or a regex with regex=true.")
                        .strs("kinds", "Limit to class, object_property, data_property, annotation_property, individual, datatype.")
                        .bool("regex", "Treat query as a case-insensitive regex.")
                        .bool("in_annotations", "Also search every annotation value.")
                        .integer("limit", "Default 50."), this::search);
        ro("describe_entity", "Show an entity's annotations, parents, children and defining axioms. Default: the entity selected in the GUI.",
                new Schema().str("entity", "IRI, prefixed name, short name or label.")
                        .str("render", RENDER), this::describe);
        tool("get_hierarchy", "Print a class or property tree as indented text.", true, false,
                new Schema().str("kind", "class (default), object_property or data_property.")
                        .str("root", "Root entity. Default: the top of the hierarchy.")
                        .integer("depth", "Default 3.")
                        .bool("inferred", "Use the inferred class hierarchy. Runs the reasoner if needed.")
                        .integer("limit", "Maximum nodes. Default 500."), this::hierarchy);
        ro("list_axioms", "List axioms, optionally those that mention an entity or have a given type. Sorted and pageable.",
                new Schema().str("entity", "Only axioms that mention this entity.")
                        .str("type", "Axiom type name, such as SubClassOf or ClassAssertion.")
                        .str("ontology", ONT + " Imports are excluded unless imports=true.")
                        .bool("imports", "Include the imports closure.")
                        .str("render", RENDER + " Default: functional.")
                        .integer("limit", "Default 100.").integer("offset", "Default 0."), this::listAxioms);
        tool("dl_query", "Answer a Manchester class expression with the reasoner, like the DL Query tab. Runs the reasoner if needed.", true, false,
                new Schema().needStr("expression", "Manchester syntax, such as: Pizza and hasTopping some Cheese")
                        .strs("queries", "Any of subclasses, superclasses, equivalents, instances. Default: all four.")
                        .bool("direct", "Direct results only. Default false.")
                        .str("reasoner", REASONER), this::dlQuery);
        tool("sparql_query", "Run a read-only SPARQL SELECT, ASK or CONSTRUCT over the asserted RDF of a snapshot of the active ontology and its imports. No inferences. Known prefixes are added automatically.", true, false,
                new Schema().needStr("query", "SPARQL query.").integer("limit", "Maximum rows returned. Default 200."), this::sparql);
        tool("classify", "Run the reasoner in Protégé and report consistency and unsatisfiable classes. Inferences then show in the GUI.", false, false,
                new Schema().str("reasoner", REASONER).integer("timeout_seconds", "Default 120."), this::classify);
        tool("explain", "Find minimal axiom sets (justifications) behind an entailment, an unsatisfiable class or an inconsistency. Uses a private copy of the ontology.", true, false,
                new Schema().str("axiom", "The entailment to explain. Omit it to explain inconsistency.")
                        .str("unsatisfiable_class", "Explain why this class is unsatisfiable, instead of `axiom`.")
                        .str("syntax", SYNTAX)
                        .integer("max", "Maximum justifications. Default 3.")
                        .integer("timeout_seconds", "Default 60.")
                        .str("reasoner", REASONER), this::explain);
        tool("what_if", "Simulate adding or removing axioms on a private copy, then report changes in consistency, unsatisfiable classes and subsumptions. Does not touch the ontology.", true, false,
                new Schema().strs("add", AXIOMS).strs("remove", "Axioms to remove, same syntax.")
                        .str("syntax", SYNTAX).integer("timeout_seconds", "Default 120.")
                        .str("reasoner", REASONER), this::whatIf);
        ro("audit", "Check quality: missing labels and definitions, duplicate labels, orphans, untyped individuals, properties without domain or range, deprecated entities in use, OWL 2 profile violations.",
                new Schema().integer("samples", "Examples per finding. Default 20.")
                        .bool("profiles", "Check OWL 2 DL, EL, QL and RL. Default true."), this::audit);

        write("add_axioms", "Add axioms in one undoable step. New entities are declared and listed in new_entities, so check that list for typos.",
                new Schema().needStrs("axioms", AXIOMS).str("syntax", SYNTAX).str("ontology", ONT)
                        .bool("dry_run", "Parse and report without changing anything."), this::addAxioms);
        write("remove_axioms", "Remove axioms in one undoable step. Reports axioms that were not found.",
                new Schema().needStrs("axioms", AXIOMS).str("syntax", SYNTAX).str("ontology", ONT)
                        .bool("dry_run", "Parse and report without changing anything."), this::removeAxioms);
        write("create_entities", "Create several entities in one undoable step, using Protégé's new-entity settings. A batch may reference its own new entities. Each item: "
                        + "kind (class, object_property, data_property, annotation_property, individual, datatype), name, "
                        + "and optional iri, parents, types (individual only), label, comment, lang, domain, range, "
                        + "characteristics (functional, inverse_functional, transitive, symmetric, asymmetric, reflexive, irreflexive).",
                new Schema().needObjects("entities", "Entities to create."), this::createEntities);
        write("rename_entity", "Change an entity's IRI everywhere, in one undoable step.",
                new Schema().needStr("entity", "Entity to rename.")
                        .str("new_iri", "New full IRI.")
                        .str("new_name", "New short name. Keeps the namespace."), this::rename);
        write("delete_entities", "Delete entities and every axiom that mentions them, in one undoable step.",
                new Schema().needStrs("entities", "Entities to delete.")
                        .bool("dry_run", "Report the axioms that would go, without deleting."), this::delete);
        write("undo", "Undo the latest change, whether it came from this server or from the GUI.",
                new Schema().integer("steps", "Default 1."), (c, a) -> history(c, a, true));
        write("redo", "Redo the latest undone change.",
                new Schema().integer("steps", "Default 1."), (c, a) -> history(c, a, false));
        ui("show_entity", "Select an entity in the Protégé GUI and switch to a tab that shows it.",
                new Schema().needStr("entity", "Entity to show."), this::showEntity);
        ui("set_active_ontology", "Change the active ontology of a window.",
                new Schema().needStr("ontology", "IRI of a loaded ontology."), this::setActive);
        tool("open_ontology", "Open an ontology file or URL the way File > Open does. The result names the new window.", false, false,
                new Schema().needStr("location", "File path or URL."), this::open);
        ui("new_ontology", "Create an empty ontology in the current window and make it active.",
                new Schema().needStr("iri", "Ontology IRI.").str("path", "File to save to later. Must be absolute."), this::newOntology);
        tool("save_ontology", "Write an ontology to disk. Without `path`, overwrites its current file, like Ctrl+S.", false, true,
                new Schema().str("ontology", ONT)
                        .str("path", "Save-as target. Must be absolute.")
                        .str("format", "rdfxml, turtle, owlxml, functional or manchester. Default: keep the current one.")
                        .bool("overwrite", "Allow `path` to replace an existing file."), this::save);
    }

    // ---- registration ----

    private void ro(String name, String desc, Schema s, ModelHandler h) {
        tool(name, desc, true, false, s, a -> onModel(a, h));
    }

    private void ui(String name, String desc, Schema s, ModelHandler h) {
        tool(name, desc, false, false, s, a -> onModel(a, h));
    }

    private void write(String name, String desc, Schema s, ModelHandler h) {
        tool(name, desc, false, false, s, a -> onModel(a, h));
    }

    private static Object onModel(Args a, ModelHandler h) throws Exception {
        return Gui.call(() -> h.call(Ctx.forKit(a), a));
    }

    private void tool(String name, String desc, boolean readOnly, boolean destructive, Schema s, Handler h) {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("readOnlyHint", readOnly);
        hints.put("destructiveHint", destructive);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name);
        spec.put("description", desc);
        spec.put("inputSchema", s.build());
        spec.put("annotations", hints);
        Tool t = new Tool();
        t.spec = spec;
        t.handler = h;
        tools.put(name, t);
    }

    List<Map<String, Object>> list() {
        return tools.values().stream().map(t -> t.spec).collect(Collectors.toList());
    }

    boolean has(String name) {
        return tools.containsKey(name);
    }

    Map<String, Object> call(String name, Map<String, Object> args) {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", Collections.singletonList(text));
        try {
            Object r = tools.get(name).handler.call(new Args(args));
            text.put("text", r instanceof String ? r : Json.write(r));
            out.put("isError", false);
        } catch (ToolError e) {
            text.put("text", e.getMessage());
            out.put("isError", true);
        } catch (Exception | LinkageError e) {
            log.warn("[protege-mcp] Tool {} failed", name, e);
            text.put("text", e.getClass().getSimpleName() + ": " + e.getMessage());
            out.put("isError", true);
        }
        return out;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static <T> List<T> head(List<T> l, int n) {
        return l.size() <= n ? l : new ArrayList<>(l.subList(0, n));
    }

    // ---- context ----

    private Object status(Ctx unused, Args a) {
        List<Object> windows = new ArrayList<>();
        List<OWLEditorKit> kits = ProtegeMcpHook.kits();
        for (int i = 0; i < kits.size(); i++) {
            OWLEditorKit k = kits.get(i);
            OWLModelManager mm = k.getOWLModelManager();
            List<Object> onts = new ArrayList<>();
            for (OWLOntology o : mm.getOntologies()) {
                onts.add(map("iri", Ctx.iriOf(o), "active", o.equals(mm.getActiveOntology()),
                        "document", mm.getOntologyPhysicalURI(o).toString(),
                        "unsaved_changes", mm.isDirty(o), "mutable", mm.isMutable(o),
                        "axioms", o.getAxiomCount()));
            }
            OWLReasonerManager rm = mm.getOWLReasonerManager();
            OWLEntity sel = k.getOWLWorkspace().getOWLSelectionModel().getSelectedEntity();
            HistoryManager h = mm.getHistoryManager();
            List<String> reasoners = rm.getInstalledReasonerFactories().stream()
                    .filter(r -> !Ctx.isNoOp(r)).map(ProtegeOWLReasonerInfo::getReasonerName)
                    .sorted().collect(Collectors.toList());
            windows.add(map("workspace", i, "ontologies", onts,
                    "selected_entity", sel == null ? null : sel.getIRI().toString(),
                    "reasoner", rm.getCurrentReasonerName(),
                    "reasoner_status", rm.getReasonerStatus().name(),
                    "installed_reasoners", reasoners,
                    "can_undo", h.canUndo(), "can_redo", h.canRedo()));
        }
        return map("windows", windows);
    }

    private Object ontologyInfo(Ctx c, Args a) throws ToolError {
        OWLOntology o = c.target(a);
        Map<String, Integer> byType = new TreeMap<>();
        for (AxiomType<?> t : AxiomType.AXIOM_TYPES) {
            int n = o.getAxiomCount(t);
            if (n > 0) {
                byType.put(t.getName(), n);
            }
        }
        List<String> annotations = new ArrayList<>();
        for (OWLAnnotation an : o.getAnnotations()) {
            annotations.add(c.mm.getRendering(an.getProperty()) + ": " + Ctx.valueText(an.getValue()));
        }
        OWLDocumentFormat f = o.getOWLOntologyManager().getOntologyFormat(o);
        return map("iri", Ctx.iriOf(o), "document", c.mm.getOntologyPhysicalURI(o).toString(),
                "format", f == null ? null : f.getKey(),
                "axioms", o.getAxiomCount(), "logical_axioms", o.getLogicalAxiomCount(),
                "axioms_by_type", byType,
                "classes", o.getClassesInSignature(Imports.EXCLUDED).size(),
                "object_properties", o.getObjectPropertiesInSignature(Imports.EXCLUDED).size(),
                "data_properties", o.getDataPropertiesInSignature(Imports.EXCLUDED).size(),
                "annotation_properties", o.getAnnotationPropertiesInSignature(Imports.EXCLUDED).size(),
                "individuals", o.getIndividualsInSignature(Imports.EXCLUDED).size(),
                "expressible_in", new DLExpressivityChecker(o.getImportsClosure()).expressibleInLanguages().stream()
                        .map(Object::toString).collect(Collectors.toList()),
                "imports", o.getDirectImportsDocuments().stream().map(IRI::toString).collect(Collectors.toList()),
                "prefixes", c.prefixes(o), "annotations", annotations);
    }

    private static final Map<String, EntityType<?>> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("class", EntityType.CLASS);
        KINDS.put("object_property", EntityType.OBJECT_PROPERTY);
        KINDS.put("data_property", EntityType.DATA_PROPERTY);
        KINDS.put("annotation_property", EntityType.ANNOTATION_PROPERTY);
        KINDS.put("individual", EntityType.NAMED_INDIVIDUAL);
        KINDS.put("datatype", EntityType.DATATYPE);
    }

    private static EntityType<?> kind(String name) throws ToolError {
        EntityType<?> t = KINDS.get(name);
        if (t == null) {
            throw new ToolError("Unknown kind '" + name + "'. Use one of " + KINDS.keySet());
        }
        return t;
    }

    private Object search(Ctx c, Args a) throws ToolError {
        String q = a.str("query");
        String lower = q.toLowerCase(Locale.ROOT);
        Pattern regex = a.bool("regex", false) ? Pattern.compile(q, Pattern.CASE_INSENSITIVE) : null;
        boolean inAnnotations = a.bool("in_annotations", false);
        int limit = a.integer("limit", 50);
        Set<EntityType<?>> kinds = new HashSet<>();
        for (String k : a.strings("kinds")) {
            kinds.add(kind(k));
        }
        Map<OWLEntity, Integer> scored = new HashMap<>();
        for (OWLEntity e : c.active().getSignature(Imports.INCLUDED)) {
            if (!kinds.isEmpty() && !kinds.contains(e.getEntityType())) {
                continue;
            }
            List<String> texts = new ArrayList<>();
            texts.add(c.mm.getRendering(e));
            texts.add(e.getIRI().getRemainder().or(""));
            texts.addAll(c.labels(e));
            texts.add(e.getIRI().toString());
            if (inAnnotations) {
                for (OWLAnnotationAssertionAxiom ax : c.annotations(e)) {
                    texts.add(Ctx.valueText(ax.getValue()));
                }
            }
            int best = Integer.MAX_VALUE;
            for (String t : texts) {
                best = Math.min(best, score(t, lower, regex));
            }
            if (best < Integer.MAX_VALUE) {
                scored.put(e, best);
            }
        }
        List<OWLEntity> ranked = new ArrayList<>(scored.keySet());
        ranked.sort((x, y) -> {
            int d = scored.get(x) - scored.get(y);
            return d != 0 ? d : c.mm.getRendering(x).compareTo(c.mm.getRendering(y));
        });
        List<Object> hits = new ArrayList<>();
        for (OWLEntity e : head(ranked, limit)) {
            hits.add(c.entity(e));
        }
        return map("total", ranked.size(), "results", hits);
    }

    private static int score(String text, String lower, Pattern regex) {
        if (regex != null) {
            return regex.matcher(text).find() ? 2 : Integer.MAX_VALUE;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (t.equals(lower)) {
            return 0;
        }
        if (t.startsWith(lower)) {
            return 1;
        }
        return t.contains(lower) ? 2 : Integer.MAX_VALUE;
    }

    private Object describe(Ctx c, Args a) throws ToolError {
        OWLEntity e;
        if (a.has("entity")) {
            e = c.resolve(a.str("entity"));
        } else {
            e = c.kit.getOWLWorkspace().getOWLSelectionModel().getSelectedEntity();
            if (e == null) {
                throw new ToolError("Nothing is selected in Protégé. Pass `entity`.");
            }
        }
        String render = a.opt("render", "manchester");
        OWLOntology o = c.active();
        Map<String, Object> out = c.entity(e);
        List<Object> annotations = new ArrayList<>();
        for (OWLAnnotationAssertionAxiom ax : c.annotations(e)) {
            Object lang = ax.getValue() instanceof OWLLiteral && ((OWLLiteral) ax.getValue()).hasLang()
                    ? ((OWLLiteral) ax.getValue()).getLang() : null;
            annotations.add(lang == null
                    ? map("property", c.mm.getRendering(ax.getProperty()), "value", Ctx.valueText(ax.getValue()))
                    : map("property", c.mm.getRendering(ax.getProperty()), "value", Ctx.valueText(ax.getValue()), "lang", lang));
        }
        out.put("annotations", annotations);
        OWLObjectHierarchyProvider<? extends OWLObject> provider = assertedProvider(c, e);
        if (provider != null) {
            hierarchyFacts(c, e, provider, out);
        }
        OWLReasonerManager rm = c.mm.getOWLReasonerManager();
        if (e.isOWLClass() && rm.getReasonerStatus() == ReasonerStatus.INITIALIZED) {
            OWLReasoner r = c.mm.getReasoner();
            OWLClass cls = e.asOWLClass();
            out.put("inferred_parents", c.names(r.getSuperClasses(cls, true).getFlattened()));
            out.put("inferred_children", c.names(r.getSubClasses(cls, true).getFlattened()));
            out.put("inferred_instances", c.names(r.getInstances(cls, false).getFlattened()));
            out.put("satisfiable", r.isSatisfiable(cls));
        }
        Set<OWLAxiom> defining = new LinkedHashSet<>();
        for (OWLOntology x : o.getImportsClosure()) {
            if (e.isOWLClass()) {
                defining.addAll(x.getAxioms(e.asOWLClass(), Imports.EXCLUDED));
            } else if (e.isOWLObjectProperty()) {
                defining.addAll(x.getAxioms(e.asOWLObjectProperty(), Imports.EXCLUDED));
            } else if (e.isOWLDataProperty()) {
                defining.addAll(x.getAxioms(e.asOWLDataProperty(), Imports.EXCLUDED));
            } else if (e.isOWLNamedIndividual()) {
                defining.addAll(x.getAxioms(e.asOWLNamedIndividual(), Imports.EXCLUDED));
            } else if (e.isOWLAnnotationProperty()) {
                defining.addAll(x.getAxioms(e.asOWLAnnotationProperty(), Imports.EXCLUDED));
            }
        }
        out.put("axioms", c.render(defining, render, o));
        out.put("mentioned_in_axioms", o.getReferencingAxioms(e, Imports.INCLUDED).size());
        out.put("deprecated", c.mm.isDeprecated(e));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends OWLObject> void hierarchyFacts(Ctx c, OWLEntity e, OWLObjectHierarchyProvider<T> p,
                                                             Map<String, Object> out) {
        T node = (T) e;
        out.put("parents", c.names(p.getParents(node)));
        out.put("children", c.names(p.getChildren(node)));
    }

    private static OWLObjectHierarchyProvider<? extends OWLObject> assertedProvider(Ctx c, OWLEntity e) {
        if (e.isOWLClass()) {
            return c.mm.getOWLHierarchyManager().getOWLClassHierarchyProvider();
        }
        if (e.isOWLObjectProperty()) {
            return c.mm.getOWLHierarchyManager().getOWLObjectPropertyHierarchyProvider();
        }
        if (e.isOWLDataProperty()) {
            return c.mm.getOWLHierarchyManager().getOWLDataPropertyHierarchyProvider();
        }
        return null;
    }

    private Object hierarchy(Args a) throws Exception {
        boolean inferred = a.bool("inferred", false);
        if (inferred) {
            ensureReasoner(a, null);
        }
        return onModel(a, (c, x) -> {
            String kindName = a.opt("kind", "class");
            OWLObjectHierarchyProvider<? extends OWLObject> p;
            switch (kindName) {
                case "class":
                    p = inferred ? c.mm.getOWLHierarchyManager().getInferredOWLClassHierarchyProvider()
                            : c.mm.getOWLHierarchyManager().getOWLClassHierarchyProvider();
                    break;
                case "object_property":
                    p = c.mm.getOWLHierarchyManager().getOWLObjectPropertyHierarchyProvider();
                    break;
                case "data_property":
                    p = c.mm.getOWLHierarchyManager().getOWLDataPropertyHierarchyProvider();
                    break;
                default:
                    throw new ToolError("kind must be class, object_property or data_property");
            }
            return tree(c, p, a);
        });
    }

    @SuppressWarnings("unchecked")
    private <T extends OWLObject> String tree(Ctx c, OWLObjectHierarchyProvider<T> p, Args a) throws ToolError {
        Set<T> roots = a.has("root")
                ? Collections.singleton((T) c.resolve(a.str("root"), KINDS.get(a.opt("kind", "class"))))
                : p.getRoots();
        StringBuilder b = new StringBuilder();
        int[] budget = {a.integer("limit", 500)};
        int depth = a.integer("depth", 3);
        for (T r : sorted(c, roots)) {
            print(c, p, r, 0, depth, new HashSet<>(), budget, b);
        }
        if (budget[0] <= 0) {
            b.append("... truncated at limit\n");
        }
        return b.toString();
    }

    private <T extends OWLObject> List<T> sorted(Ctx c, Set<T> s) {
        List<T> l = new ArrayList<>(s);
        l.sort((x, y) -> c.mm.getRendering(x).compareTo(c.mm.getRendering(y)));
        return l;
    }

    private <T extends OWLObject> void print(Ctx c, OWLObjectHierarchyProvider<T> p, T node, int level, int depth,
                                             Set<T> path, int[] budget, StringBuilder b) {
        if (budget[0]-- <= 0) {
            return;
        }
        b.append("  ".repeat(level)).append(c.name(node));
        Set<T> kids = p.getChildren(node);
        if (level >= depth && !kids.isEmpty()) {
            b.append(" (+").append(kids.size()).append(')');
        }
        b.append('\n');
        if (level >= depth || !path.add(node)) {
            return;
        }
        for (T k : sorted(c, kids)) {
            print(c, p, k, level + 1, depth, path, budget, b);
        }
        path.remove(node);
    }

    private Object listAxioms(Ctx c, Args a) throws ToolError {
        OWLOntology o = c.target(a);
        Imports imports = a.bool("imports", false) ? Imports.INCLUDED : Imports.EXCLUDED;
        Set<OWLAxiom> axioms;
        if (a.has("entity")) {
            axioms = new LinkedHashSet<>(o.getReferencingAxioms(c.resolve(a.str("entity")), imports));
        } else {
            axioms = new LinkedHashSet<>(o.getAxioms(imports));
        }
        if (a.has("type")) {
            AxiomType<?> t = AxiomType.getAxiomType(a.str("type"));
            if (t == null) {
                throw new ToolError("Unknown axiom type: " + a.str("type"));
            }
            axioms.removeIf(ax -> !ax.isOfType(t));
        }
        List<String> all = c.render(axioms, a.opt("render", "functional"), o);
        int offset = Math.min(a.integer("offset", 0), all.size());
        int end = Math.min(offset + a.integer("limit", 100), all.size());
        return map("total", all.size(), "offset", offset, "axioms", all.subList(offset, end));
    }

    // ---- reasoning ----

    private static void sleep() throws InterruptedException {
        Thread.sleep(100);
    }

    /** Runs Protégé's reasoner and waits. Must not run on the Swing thread. */
    private Object classify(Args a) throws Exception {
        long timeout = a.integer("timeout_seconds", 120) * 1000L;
        Boolean finished = runClassification(a, a.opt("reasoner", null), timeout);
        return Gui.call(() -> {
            Ctx c = Ctx.forKit(a);
            OWLReasonerManager rm = c.mm.getOWLReasonerManager();
            ReasonerStatus st = rm.getReasonerStatus();
            Map<String, Object> out = map("reasoner", rm.getCurrentReasonerName(), "status", st.name(),
                    "finished", finished);
            if (st == ReasonerStatus.INCONSISTENT) {
                out.put("consistent", false);
                out.put("hint", "Use `explain` with no target. Protégé may also show an inconsistency dialog.");
            } else if (st == ReasonerStatus.INITIALIZED) {
                OWLReasoner r = c.mm.getReasoner();
                Set<OWLClass> unsat = new HashSet<>(r.getUnsatisfiableClasses().getEntities());
                unsat.remove(c.df.getOWLNothing());
                out.put("consistent", true);
                out.put("unsatisfiable_classes", c.names(unsat));
            }
            return out;
        });
    }

    private Boolean runClassification(Args a, String wanted, long timeoutMs) throws Exception {
        OWLReasonerManager rm = Gui.call(() -> {
            Ctx c = Ctx.forKit(a);
            OWLReasonerManager m = c.mm.getOWLReasonerManager();
            ProtegeOWLReasonerInfo info = c.pickReasoner(wanted);
            if (!info.getReasonerId().equals(m.getCurrentReasonerFactoryId())) {
                m.setCurrentReasonerFactoryId(info.getReasonerId());
            }
            if (!m.classifyAsynchronously(m.getReasonerPreferences().getPrecomputedInferences())) {
                throw new ToolError("A classification is already running.");
            }
            return m;
        });
        long end = System.currentTimeMillis() + timeoutMs;
        while (rm.isClassificationInProgress()) {
            if (System.currentTimeMillis() > end) {
                return false;
            }
            sleep();
        }
        return true;
    }

    /** Makes the live reasoner current, classifying when needed. */
    private void ensureReasoner(Args a, String wanted) throws Exception {
        ReasonerStatus st = Gui.call(() -> Ctx.forKit(a).mm.getOWLReasonerManager().getReasonerStatus());
        if (st != ReasonerStatus.INITIALIZED && st != ReasonerStatus.INCONSISTENT || wanted != null) {
            if (!runClassification(a, wanted, 300_000)) {
                throw new ToolError("The reasoner is still running. Try again later.");
            }
            st = Gui.call(() -> Ctx.forKit(a).mm.getOWLReasonerManager().getReasonerStatus());
        }
        if (st == ReasonerStatus.INCONSISTENT) {
            throw new ToolError("The ontology is inconsistent, so every query is trivially true. Use `explain`.");
        }
        if (st != ReasonerStatus.INITIALIZED) {
            throw new ToolError("The reasoner is not ready. Status: " + st);
        }
    }

    private Object dlQuery(Args a) throws Exception {
        ensureReasoner(a, a.opt("reasoner", null));
        return onModel(a, (c, x) -> {
            OWLClassExpression ce = c.parseClassExpression(a.str("expression"));
            OWLReasoner r = c.mm.getReasoner();
            boolean direct = a.bool("direct", false);
            List<String> wanted = a.strings("queries");
            if (wanted.isEmpty()) {
                wanted = Arrays.asList("equivalents", "superclasses", "subclasses", "instances");
            }
            Map<String, Object> out = map("satisfiable", r.isSatisfiable(ce));
            for (String q : wanted) {
                Set<? extends OWLEntity> hits;
                switch (q) {
                    case "subclasses":
                        hits = r.getSubClasses(ce, direct).getFlattened();
                        break;
                    case "superclasses":
                        hits = r.getSuperClasses(ce, direct).getFlattened();
                        break;
                    case "equivalents":
                        hits = r.getEquivalentClasses(ce).getEntities();
                        break;
                    case "instances":
                        hits = r.getInstances(ce, direct).getFlattened();
                        break;
                    default:
                        throw new ToolError("Unknown query: " + q);
                }
                Set<OWLEntity> shown = new HashSet<>(hits);
                shown.remove(c.df.getOWLNothing());
                out.put(q, c.names(shown));
            }
            return out;
        });
    }

    private Object sparql(Args a) throws Exception {
        String[] prepared = new String[1];
        OWLOntologyManager snapshot = OWLManager.createOWLOntologyManager();
        Set<OWLAxiom> axioms = Gui.call(() -> {
            Ctx c = Ctx.forKit(a);
            StringBuilder q = new StringBuilder();
            String text = a.str("query");
            c.prefixes(c.active()).forEach((k, v) -> {
                if (!Pattern.compile("(?i)PREFIX\\s+" + Pattern.quote(k)).matcher(text).find()) {
                    q.append("PREFIX ").append(k).append(" <").append(v).append(">\n");
                }
            });
            prepared[0] = q + text;
            Set<OWLAxiom> all = new HashSet<>();
            for (OWLOntology o : c.active().getImportsClosure()) {
                all.addAll(o.getAxioms());
            }
            return all;
        });
        OWLOntology copy = snapshot.createOntology(axioms);
        StringDocumentTarget turtle = new StringDocumentTarget();
        snapshot.saveOntology(copy, new TurtleDocumentFormat(), turtle);
        try {
            return Sparql.run(turtle.toString(), prepared[0], a.integer("limit", 200));
        } catch (NoClassDefFoundError e) {
            log.warn("[protege-mcp] SPARQL unavailable", e);
            throw new ToolError("SPARQL needs the rdf-library plugin: " + e.getMessage());
        }
    }

    /** Everything a background reasoning task needs, captured on the Swing thread. */
    private static final class Snapshot {
        OWLReasonerFactory factory;
        OWLReasonerConfiguration config;
        String reasoner;
        Set<OWLAxiom> axioms;
        OWLAxiom target;
        Set<OWLAxiom> add = Collections.emptySet();
        Set<OWLAxiom> remove = Collections.emptySet();
        Map<OWLEntity, String> names = new HashMap<>();
        Ctx ctx;
    }

    private static Snapshot snapshot(Ctx c, Args a) throws ToolError {
        ProtegeOWLReasonerInfo info = c.pickReasoner(a.opt("reasoner", null));
        Snapshot s = new Snapshot();
        s.factory = info.getReasonerFactory();
        s.config = info.getConfiguration(new NullReasonerProgressMonitor());
        s.reasoner = info.getReasonerName();
        s.axioms = Ctx.logicalAxioms(c.active());
        s.ctx = c;
        return s;
    }

    private static <T> T bounded(long timeoutMs, Callable<T> work, Runnable onTimeout) throws Exception {
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "protege-mcp-reasoning");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<T> f = ex.submit(work);
            try {
                return f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                onTimeout.run();
                f.cancel(true);
                return null;
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof Exception) {
                    throw (Exception) e.getCause();
                }
                throw new RuntimeException(e.getCause());
            }
        } finally {
            ex.shutdown();
        }
    }

    private Object explain(Args a) throws Exception {
        Snapshot s = Gui.call(() -> {
            Ctx c = Ctx.forKit(a);
            Snapshot sn = snapshot(c, a);
            if (a.has("unsatisfiable_class")) {
                OWLClass cls = c.resolve(a.str("unsatisfiable_class"), EntityType.CLASS).asOWLClass();
                sn.target = c.df.getOWLSubClassOfAxiom(cls, c.df.getOWLNothing());
            } else if (a.has("axiom")) {
                sn.target = c.parseAxioms(List.of(a.str("axiom")), a.opt("syntax", "functional"), c.active())
                        .iterator().next();
            }
            return sn;
        });
        long timeout = a.integer("timeout_seconds", 60) * 1000L;
        Justifier j = new Justifier(s.factory, s.config, s.target, System.currentTimeMillis() + timeout);
        boolean[] entailed = {true};
        bounded(timeout + 5000, () -> {
            Set<OWLAxiom> universe = j.module(s.axioms);
            if (!j.holds(universe)) {
                entailed[0] = false;
                return null;
            }
            j.search(universe, a.integer("max", 3));
            return null;
        }, j::interrupt);
        if (!entailed[0]) {
            throw new ToolError(s.target == null ? "The ontology is consistent: nothing to explain."
                    : "The ontology does not entail that axiom.");
        }
        List<Set<OWLAxiom>> found;
        synchronized (j.found) {
            found = new ArrayList<>(j.found);
        }
        return Gui.call(() -> {
            Ctx c = Ctx.forKit(a);
            List<Object> out = new ArrayList<>();
            for (Set<OWLAxiom> just : found) {
                out.add(map("manchester", c.render(just, "manchester", c.active()),
                        "functional", c.render(just, "functional", c.active())));
            }
            return map("reasoner", s.reasoner, "justifications", out, "search_complete", j.complete);
        });
    }

    private static final class Classified {
        boolean consistent;
        Set<OWLClass> unsat = new HashSet<>();
        Map<OWLClass, Set<OWLClass>> supers = new HashMap<>();
    }

    private static Classified classifyCopy(Snapshot s, Set<OWLAxiom> axioms, OWLReasoner[] running) throws Exception {
        OWLOntologyManager m = OWLManager.createOWLOntologyManager();
        OWLOntology o = m.createOntology(axioms);
        OWLReasoner r = s.factory.createNonBufferingReasoner(o, s.config);
        running[0] = r;
        Classified out = new Classified();
        try {
            out.consistent = r.isConsistent();
            if (!out.consistent) {
                return out;
            }
            r.precomputeInferences(InferenceType.CLASS_HIERARCHY);
            out.unsat.addAll(r.getUnsatisfiableClasses().getEntitiesMinusBottom());
            for (OWLClass cls : o.getClassesInSignature()) {
                if (!out.unsat.contains(cls)) {
                    out.supers.put(cls, new HashSet<>(r.getSuperClasses(cls, false).getFlattened()));
                }
            }
            return out;
        } finally {
            running[0] = null;
            r.dispose();
        }
    }

    private Object whatIf(Args a) throws Exception {
        if (a.strings("add").isEmpty() && a.strings("remove").isEmpty()) {
            throw new ToolError("Pass `add`, `remove` or both.");
        }
        Snapshot s = Gui.call(() -> {
            Ctx c = Ctx.forKit(a);
            Snapshot sn = snapshot(c, a);
            String syntax = a.opt("syntax", "functional");
            sn.add = c.parseAxioms(a.strings("add"), syntax, c.active());
            sn.remove = c.parseAxioms(a.strings("remove"), syntax, c.active());
            return sn;
        });
        Set<OWLAxiom> after = new HashSet<>(s.axioms);
        after.removeAll(s.remove);
        for (OWLAxiom ax : s.add) {
            if (ax.isLogicalAxiom()) {
                after.add(ax);
            }
        }
        OWLReasoner[] running = new OWLReasoner[1];
        Classified[] both = bounded(a.integer("timeout_seconds", 120) * 1000L,
                () -> new Classified[]{classifyCopy(s, s.axioms, running), classifyCopy(s, after, running)},
                () -> {
                    if (running[0] != null) {
                        running[0].interrupt();
                    }
                });
        if (both == null) {
            throw new ToolError("Simulation timed out. Nothing changed.");
        }
        return Gui.call(() -> whatIfReport(Ctx.forKit(a), s, both[0], both[1]));
    }

    private Object whatIfReport(Ctx c, Snapshot s, Classified before, Classified after) {
        Map<String, Object> out = map("reasoner", s.reasoner, "consistent_before", before.consistent,
                "consistent_after", after.consistent);
        if (!before.consistent || !after.consistent) {
            return out;
        }
        Set<OWLClass> newUnsat = new HashSet<>(after.unsat);
        newUnsat.removeAll(before.unsat);
        Set<OWLClass> fixed = new HashSet<>(before.unsat);
        fixed.removeAll(after.unsat);
        List<String> gained = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        for (Map.Entry<OWLClass, Set<OWLClass>> e : after.supers.entrySet()) {
            Set<OWLClass> old = before.supers.getOrDefault(e.getKey(), Collections.emptySet());
            diff(c, e.getKey(), e.getValue(), old, gained);
            diff(c, e.getKey(), old, e.getValue(), lost);
        }
        Collections.sort(gained);
        Collections.sort(lost);
        out.put("newly_unsatisfiable", c.names(newUnsat));
        out.put("no_longer_unsatisfiable", c.names(fixed));
        out.put("subsumptions_gained", head(gained, 100));
        out.put("subsumptions_lost", head(lost, 100));
        out.put("subsumptions_gained_total", gained.size());
        out.put("subsumptions_lost_total", lost.size());
        return out;
    }

    private static void diff(Ctx c, OWLClass sub, Set<OWLClass> a, Set<OWLClass> b, List<String> out) {
        for (OWLClass sup : a) {
            if (!b.contains(sup) && !sup.isOWLThing()) {
                out.add(c.name(sub) + " SubClassOf " + c.name(sup));
            }
        }
    }

    // ---- quality ----

    private Object audit(Ctx c, Args a) throws ToolError {
        OWLOntology o = c.active();
        int samples = a.integer("samples", 20);
        Map<String, Object> out = new LinkedHashMap<>();
        List<OWLEntity> noLabel = new ArrayList<>();
        List<OWLEntity> noDefinition = new ArrayList<>();
        Map<String, Set<OWLEntity>> byLabel = new TreeMap<>();
        for (OWLEntity e : o.getSignature(Imports.EXCLUDED)) {
            if (e.isBuiltIn() || e.isOWLDatatype()) {
                continue;
            }
            List<String> labels = c.labels(e);
            if (labels.isEmpty()) {
                noLabel.add(e);
            }
            labels.forEach(l -> byLabel.computeIfAbsent(l.toLowerCase(Locale.ROOT), k -> new HashSet<>()).add(e));
            boolean defined = c.annotations(e).stream().anyMatch(ax -> {
                String p = ax.getProperty().getIRI().toString().toLowerCase(Locale.ROOT);
                return p.endsWith("#comment") || p.contains("definition") || p.endsWith("iao_0000115");
            });
            if (!defined) {
                noDefinition.add(e);
            }
        }
        out.put("no_label", finding(c, noLabel, samples));
        out.put("no_definition_or_comment", finding(c, noDefinition, samples));
        List<String> dup = new ArrayList<>();
        byLabel.forEach((label, es) -> {
            if (es.size() > 1) {
                dup.add(label + ": " + es.stream().map(e -> e.getIRI().toString()).sorted()
                        .collect(Collectors.joining(", ")));
            }
        });
        out.put("duplicate_labels", map("count", dup.size(), "examples", head(dup, samples)));

        List<OWLEntity> orphans = new ArrayList<>();
        for (OWLClass cls : o.getClassesInSignature(Imports.EXCLUDED)) {
            if (!cls.isBuiltIn() && o.getReferencingAxioms(cls, Imports.INCLUDED).stream()
                    .noneMatch(OWLAxiom::isLogicalAxiom)) {
                orphans.add(cls);
            }
        }
        out.put("classes_in_no_logical_axiom", finding(c, orphans, samples));

        List<OWLEntity> untyped = new ArrayList<>();
        for (OWLNamedIndividual i : o.getIndividualsInSignature(Imports.EXCLUDED)) {
            boolean typed = o.getImportsClosure().stream().anyMatch(x -> !x.getClassAssertionAxioms(i).isEmpty());
            if (!typed) {
                untyped.add(i);
            }
        }
        out.put("individuals_without_type", finding(c, untyped, samples));

        List<OWLEntity> noDomainRange = new ArrayList<>();
        for (OWLObjectProperty p : o.getObjectPropertiesInSignature(Imports.EXCLUDED)) {
            if (!p.isBuiltIn() && (o.getObjectPropertyDomainAxioms(p).isEmpty() || o.getObjectPropertyRangeAxioms(p).isEmpty())) {
                noDomainRange.add(p);
            }
        }
        for (OWLDataProperty p : o.getDataPropertiesInSignature(Imports.EXCLUDED)) {
            if (!p.isBuiltIn() && (o.getDataPropertyDomainAxioms(p).isEmpty() || o.getDataPropertyRangeAxioms(p).isEmpty())) {
                noDomainRange.add(p);
            }
        }
        out.put("properties_without_domain_or_range", finding(c, noDomainRange, samples));

        List<OWLEntity> deprecatedInUse = new ArrayList<>();
        for (OWLEntity e : o.getSignature(Imports.EXCLUDED)) {
            if (c.mm.isDeprecated(e) && o.getReferencingAxioms(e, Imports.INCLUDED).stream()
                    .anyMatch(OWLAxiom::isLogicalAxiom)) {
                deprecatedInUse.add(e);
            }
        }
        out.put("deprecated_in_use", finding(c, deprecatedInUse, samples));

        if (a.bool("profiles", true)) {
            Map<String, Object> profiles = new LinkedHashMap<>();
            for (Profiles p : Arrays.asList(Profiles.OWL2_DL, Profiles.OWL2_EL, Profiles.OWL2_QL, Profiles.OWL2_RL)) {
                OWLProfileReport report = p.checkOntology(o);
                List<OWLProfileViolation> v = report.getViolations();
                profiles.put(p.name(), map("conforms", report.isInProfile(), "violations", v.size(),
                        "examples", head(v, Math.min(samples, 5)).stream().map(Object::toString)
                                .collect(Collectors.toList())));
            }
            out.put("owl2_profiles", profiles);
        }
        OWLReasonerManager rm = c.mm.getOWLReasonerManager();
        ReasonerStatus st = rm.getReasonerStatus();
        out.put("reasoner_status", st.name());
        if (st == ReasonerStatus.INITIALIZED) {
            out.put("unsatisfiable_classes", c.names(c.mm.getReasoner().getUnsatisfiableClasses().getEntitiesMinusBottom()));
        }
        return out;
    }

    private static Map<String, Object> finding(Ctx c, List<? extends OWLEntity> es, int samples) {
        return map("count", es.size(), "examples", head(c.names(es), samples));
    }

    // ---- editing ----

    private Object addAxioms(Ctx c, Args a) throws ToolError {
        OWLOntology o = c.mutableTarget(a);
        Set<OWLAxiom> parsed = c.parseAxioms(a.strings("axioms"), a.opt("syntax", "functional"), o);
        List<OWLOntologyChange> changes = new ArrayList<>();
        Set<OWLEntity> fresh = new LinkedHashSet<>();
        int present = 0;
        for (OWLAxiom ax : parsed) {
            if (o.containsAxiom(ax)) {
                present++;
                continue;
            }
            changes.add(new AddAxiom(o, ax));
            for (OWLEntity e : ax.getSignature()) {
                if (!e.isBuiltIn() && !o.containsEntityInSignature(e, Imports.INCLUDED)) {
                    fresh.add(e);
                }
            }
        }
        for (OWLEntity e : fresh) {
            changes.add(new AddAxiom(o, c.df.getOWLDeclarationAxiom(e)));
        }
        boolean dry = a.bool("dry_run", false);
        if (!dry && !changes.isEmpty()) {
            c.mm.applyChanges(changes);
        }
        return map("dry_run", dry, "added", parsed.size() - present, "already_present", present,
                "new_entities", fresh.stream().map(e -> e.getEntityType().getName() + " " + e.getIRI())
                        .collect(Collectors.toList()));
    }

    private Object removeAxioms(Ctx c, Args a) throws ToolError {
        OWLOntology o = c.mutableTarget(a);
        Set<OWLAxiom> parsed = c.parseAxioms(a.strings("axioms"), a.opt("syntax", "functional"), o);
        List<OWLOntologyChange> changes = new ArrayList<>();
        List<OWLAxiom> missing = new ArrayList<>();
        for (OWLAxiom ax : parsed) {
            if (o.containsAxiom(ax)) {
                changes.add(new RemoveAxiom(o, ax));
            } else {
                missing.add(ax);
            }
        }
        boolean dry = a.bool("dry_run", false);
        if (!dry && !changes.isEmpty()) {
            c.mm.applyChanges(changes);
        }
        return map("dry_run", dry, "removed", changes.size(), "not_found", c.render(missing, "functional", o));
    }

    private Object createEntities(Ctx c, Args a) throws Exception {
        OWLOntology o = c.mutableTarget(new Args(null));
        List<Args> specs = a.objects("entities");
        Map<String, OWLEntity> batch = new HashMap<>();
        List<OWLEntity> created = new ArrayList<>();
        List<OWLOntologyChange> changes = new ArrayList<>();
        for (Args s : specs) {
            String name = s.str("name");
            EntityType<?> t = kind(s.str("kind"));
            OWLEntity e;
            if (s.has("iri")) {
                IRI iri = c.expand(s.str("iri"));
                if (iri == null) {
                    throw new ToolError("Not an IRI: " + s.str("iri"));
                }
                e = c.df.getOWLEntity(t, iri);
                if (o.containsEntityInSignature(e, Imports.INCLUDED)) {
                    throw new ToolError("Already exists: " + iri);
                }
                changes.add(new AddAxiom(o, c.df.getOWLDeclarationAxiom(e)));
            } else {
                OWLEntityCreationSet<? extends OWLEntity> set = newEntity(c, t, name);
                e = set.getOWLEntity();
                changes.addAll(set.getOntologyChanges());
            }
            batch.put(name, e);
            created.add(e);
        }
        for (int i = 0; i < specs.size(); i++) {
            for (OWLAxiom ax : entityAxioms(c, specs.get(i), created.get(i), batch)) {
                changes.add(new AddAxiom(o, ax));
            }
        }
        c.mm.applyChanges(changes);
        List<Object> out = new ArrayList<>();
        for (OWLEntity e : created) {
            out.add(c.entity(e));
        }
        return map("created", out);
    }

    private static OWLEntityCreationSet<? extends OWLEntity> newEntity(Ctx c, EntityType<?> t, String name) throws Exception {
        if (t == EntityType.CLASS) {
            return c.mm.getOWLEntityFactory().createOWLClass(name, null);
        }
        if (t == EntityType.OBJECT_PROPERTY) {
            return c.mm.getOWLEntityFactory().createOWLObjectProperty(name, null);
        }
        if (t == EntityType.DATA_PROPERTY) {
            return c.mm.getOWLEntityFactory().createOWLDataProperty(name, null);
        }
        if (t == EntityType.ANNOTATION_PROPERTY) {
            return c.mm.getOWLEntityFactory().createOWLAnnotationProperty(name, null);
        }
        if (t == EntityType.NAMED_INDIVIDUAL) {
            return c.mm.getOWLEntityFactory().createOWLIndividual(name, null);
        }
        return c.mm.getOWLEntityFactory().createOWLDatatype(name, null);
    }

    private static OWLEntity ref(Ctx c, String name, EntityType<?> t, Map<String, OWLEntity> batch) throws ToolError {
        OWLEntity e = batch.get(name);
        if (e != null && e.getEntityType().equals(t)) {
            return e;
        }
        return c.resolve(name, t);
    }

    private static List<OWLAxiom> entityAxioms(Ctx c, Args s, OWLEntity e, Map<String, OWLEntity> batch) throws ToolError {
        List<OWLAxiom> out = new ArrayList<>();
        String lang = s.opt("lang", "");
        if (s.has("label")) {
            out.add(c.df.getOWLAnnotationAssertionAxiom(c.df.getRDFSLabel(), e.getIRI(), c.df.getOWLLiteral(s.str("label"), lang)));
        }
        if (s.has("comment")) {
            out.add(c.df.getOWLAnnotationAssertionAxiom(c.df.getRDFSComment(), e.getIRI(), c.df.getOWLLiteral(s.str("comment"), lang)));
        }
        for (String p : s.strings("parents")) {
            OWLEntity parent = ref(c, p, e.getEntityType(), batch);
            if (e.isOWLClass()) {
                out.add(c.df.getOWLSubClassOfAxiom(e.asOWLClass(), parent.asOWLClass()));
            } else if (e.isOWLObjectProperty()) {
                out.add(c.df.getOWLSubObjectPropertyOfAxiom(e.asOWLObjectProperty(), parent.asOWLObjectProperty()));
            } else if (e.isOWLDataProperty()) {
                out.add(c.df.getOWLSubDataPropertyOfAxiom(e.asOWLDataProperty(), parent.asOWLDataProperty()));
            } else if (e.isOWLAnnotationProperty()) {
                out.add(c.df.getOWLSubAnnotationPropertyOfAxiom(e.asOWLAnnotationProperty(), parent.asOWLAnnotationProperty()));
            } else {
                throw new ToolError("`parents` does not apply to " + e.getEntityType().getName() + ". Use `types`.");
            }
        }
        for (String t : s.strings("types")) {
            if (!e.isOWLNamedIndividual()) {
                throw new ToolError("`types` applies to individuals only.");
            }
            out.add(c.df.getOWLClassAssertionAxiom(ref(c, t, EntityType.CLASS, batch).asOWLClass(), e.asOWLNamedIndividual()));
        }
        for (String d : s.strings("domain")) {
            OWLClass cls = ref(c, d, EntityType.CLASS, batch).asOWLClass();
            if (e.isOWLObjectProperty()) {
                out.add(c.df.getOWLObjectPropertyDomainAxiom(e.asOWLObjectProperty(), cls));
            } else if (e.isOWLDataProperty()) {
                out.add(c.df.getOWLDataPropertyDomainAxiom(e.asOWLDataProperty(), cls));
            } else {
                throw new ToolError("`domain` applies to object and data properties only.");
            }
        }
        for (String r : s.strings("range")) {
            if (e.isOWLObjectProperty()) {
                out.add(c.df.getOWLObjectPropertyRangeAxiom(e.asOWLObjectProperty(), ref(c, r, EntityType.CLASS, batch).asOWLClass()));
            } else if (e.isOWLDataProperty()) {
                out.add(c.df.getOWLDataPropertyRangeAxiom(e.asOWLDataProperty(), datatype(c, r)));
            } else {
                throw new ToolError("`range` applies to object and data properties only.");
            }
        }
        for (String ch : s.strings("characteristics")) {
            out.add(characteristic(c, e, ch));
        }
        return out;
    }

    private static OWLDataRange datatype(Ctx c, String ref) throws ToolError {
        IRI iri = c.expand(ref.contains(":") ? ref : "xsd:" + ref);
        if (iri == null) {
            throw new ToolError("Unknown datatype: " + ref);
        }
        OWLDatatype d = c.df.getOWLDatatype(iri);
        return d;
    }

    private static OWLAxiom characteristic(Ctx c, OWLEntity e, String ch) throws ToolError {
        if (e.isOWLDataProperty() && ch.equals("functional")) {
            return c.df.getOWLFunctionalDataPropertyAxiom(e.asOWLDataProperty());
        }
        if (!e.isOWLObjectProperty()) {
            throw new ToolError("Characteristic '" + ch + "' does not apply to " + e.getEntityType().getName());
        }
        OWLObjectProperty p = e.asOWLObjectProperty();
        switch (ch) {
            case "functional": return c.df.getOWLFunctionalObjectPropertyAxiom(p);
            case "inverse_functional": return c.df.getOWLInverseFunctionalObjectPropertyAxiom(p);
            case "transitive": return c.df.getOWLTransitiveObjectPropertyAxiom(p);
            case "symmetric": return c.df.getOWLSymmetricObjectPropertyAxiom(p);
            case "asymmetric": return c.df.getOWLAsymmetricObjectPropertyAxiom(p);
            case "reflexive": return c.df.getOWLReflexiveObjectPropertyAxiom(p);
            case "irreflexive": return c.df.getOWLIrreflexiveObjectPropertyAxiom(p);
            default: throw new ToolError("Unknown characteristic: " + ch);
        }
    }

    private Object rename(Ctx c, Args a) throws ToolError {
        OWLEntity e = c.resolve(a.str("entity"));
        IRI to;
        if (a.has("new_iri")) {
            to = c.expand(a.str("new_iri"));
            if (to == null) {
                throw new ToolError("Not an IRI: " + a.str("new_iri"));
            }
        } else {
            to = IRI.create(e.getIRI().getNamespace() + a.str("new_name"));
        }
        if (!c.active().getEntitiesInSignature(to, Imports.INCLUDED).isEmpty()) {
            throw new ToolError("An entity already uses " + to + ". Renaming would merge them.");
        }
        OWLEntityRenamer renamer = new OWLEntityRenamer(c.mm.getOWLOntologyManager(), c.mm.getOntologies());
        List<OWLOntologyChange> changes = renamer.changeIRI(e, to);
        c.mm.applyChanges(changes);
        return map("old_iri", e.getIRI().toString(), "new_iri", to.toString(), "changes", changes.size());
    }

    private Object delete(Ctx c, Args a) throws ToolError {
        OWLEntityRemover remover = new OWLEntityRemover(c.mm.getOntologies());
        List<Object> deleted = new ArrayList<>();
        for (String ref : a.strings("entities")) {
            OWLEntity e = c.resolve(ref);
            e.accept(remover);
            deleted.add(c.entity(e));
        }
        List<RemoveAxiom> changes = remover.getChanges();
        Set<OWLAxiom> axioms = new LinkedHashSet<>();
        for (RemoveAxiom r : changes) {
            axioms.add(r.getAxiom());
        }
        boolean dry = a.bool("dry_run", false);
        Map<String, Object> out = map("dry_run", dry, "entities", deleted, "axioms_removed", changes.size());
        if (dry) {
            out.put("axioms", head(c.render(axioms, "functional", c.active()), 100));
        } else {
            c.mm.applyChanges(changes);
        }
        return out;
    }

    private Object history(Ctx c, Args a, boolean undo) throws ToolError {
        HistoryManager h = c.mm.getHistoryManager();
        int done = 0;
        for (int i = 0; i < a.integer("steps", 1); i++) {
            if (undo ? !h.canUndo() : !h.canRedo()) {
                break;
            }
            if (undo) {
                h.undo();
            } else {
                h.redo();
            }
            done++;
        }
        return map("steps_done", done, "can_undo", h.canUndo(), "can_redo", h.canRedo());
    }

    // ---- workspace and files ----

    private Object showEntity(Ctx c, Args a) throws ToolError {
        OWLEntity e = c.resolve(a.str("entity"));
        c.kit.getOWLWorkspace().getOWLSelectionModel().setSelectedEntity(e);
        c.kit.getOWLWorkspace().displayOWLEntity(e);
        return c.entity(e);
    }

    private Object setActive(Ctx c, Args a) throws ToolError {
        OWLOntology o = c.target(a);
        c.mm.setActiveOntology(o);
        return map("active", Ctx.iriOf(o));
    }

    private Object open(Args a) throws Exception {
        String location = a.str("location");
        URI uri = location.matches("^[a-zA-Z][a-zA-Z0-9+.-]+:.*")
                ? URI.create(location) : Paths.get(location).toAbsolutePath().toUri();
        if ("file".equals(uri.getScheme()) && !Files.isRegularFile(Paths.get(uri))) {
            throw new ToolError("No such file: " + location);
        }
        return Gui.call(600_000, () -> {
            ProtegeManager pm = ProtegeManager.getInstance();
            EditorKitFactoryPlugin plugin = null;
            for (EditorKitFactoryPlugin p : pm.getEditorKitFactoryPlugins()) {
                if (p.getId().contains("OWL")) {
                    plugin = p;
                }
            }
            if (plugin == null) {
                throw new ToolError("Protégé has no OWL editor kit factory.");
            }
            if (!pm.loadAndSetupEditorKitFromURI(plugin, uri)) {
                throw new ToolError("Protégé did not load " + location + ". Check its error dialog.");
            }
            List<OWLEditorKit> kits = ProtegeMcpHook.kits();
            OWLEditorKit kit = kits.get(kits.size() - 1);
            OWLOntology o = kit.getOWLModelManager().getActiveOntology();
            return map("workspace", kits.size() - 1, "ontology", Ctx.iriOf(o), "axioms", o.getAxiomCount());
        });
    }

    private Object newOntology(Ctx c, Args a) throws Exception {
        IRI iri = IRI.create(a.str("iri"));
        URI doc = a.has("path") ? absolute(a.str("path")).toUri() : iri.toURI();
        OWLOntology o = c.mm.createNewOntology(new OWLOntologyID(
                com.google.common.base.Optional.of(iri), com.google.common.base.Optional.absent()), doc);
        return map("ontology", Ctx.iriOf(o), "document", doc.toString());
    }

    private static Path absolute(String path) throws ToolError {
        Path p = Paths.get(path);
        if (!p.isAbsolute()) {
            throw new ToolError("Path must be absolute: " + path);
        }
        return p;
    }

    private static OWLDocumentFormat format(String name) throws ToolError {
        switch (name) {
            case "rdfxml": return new RDFXMLDocumentFormat();
            case "turtle": return new TurtleDocumentFormat();
            case "owlxml": return new OWLXMLDocumentFormat();
            case "functional": return new FunctionalSyntaxDocumentFormat();
            case "manchester": return new ManchesterSyntaxDocumentFormat();
            default: throw new ToolError("Unknown format: " + name);
        }
    }

    private Object save(Args a) throws Exception {
        return Gui.call(300_000, () -> {
            Ctx c = Ctx.forKit(a);
            OWLOntology o = c.target(a);
            OWLOntologyManager m = c.mm.getOWLOntologyManager();
            if (a.has("path")) {
                Path p = absolute(a.str("path"));
                URI current = c.mm.getOntologyPhysicalURI(o);
                if (Files.exists(p) && !p.toUri().equals(current) && !a.bool("overwrite", false)) {
                    throw new ToolError("File exists: " + p + ". Pass overwrite=true to replace it.");
                }
                c.mm.setPhysicalURI(o, p.toUri());
            } else if (!"file".equals(c.mm.getOntologyPhysicalURI(o).getScheme())) {
                throw new ToolError("This ontology has no file yet. Pass `path`.");
            }
            if (a.has("format")) {
                OWLDocumentFormat f = format(a.str("format"));
                OWLDocumentFormat old = m.getOntologyFormat(o);
                if (f instanceof PrefixDocumentFormat && old instanceof PrefixDocumentFormat) {
                    ((PrefixDocumentFormat) f).copyPrefixesFrom((PrefixDocumentFormat) old);
                }
                m.setOntologyFormat(o, f);
            }
            c.mm.save(o);
            return map("saved", c.mm.getOntologyPhysicalURI(o).toString(), "format", m.getOntologyFormat(o).getKey(),
                    "axioms", o.getAxiomCount());
        });
    }
}
