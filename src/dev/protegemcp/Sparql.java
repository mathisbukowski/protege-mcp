package dev.protegemcp;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openrdf.model.Statement;
import org.openrdf.model.Value;
import org.openrdf.query.BindingSet;
import org.openrdf.query.BooleanQuery;
import org.openrdf.query.GraphQuery;
import org.openrdf.query.GraphQueryResult;
import org.openrdf.query.Query;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.TupleQuery;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.repository.util.RDFInserter;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.turtle.TurtleParser;
import org.openrdf.sail.memory.MemoryStore;

/**
 * Evaluates SPARQL with the Sesame engine that the rdf-library plugin exports. Protégé's own
 * OwlTripleStore cannot be used: rdf-library 3.0.1 does not import Guava, so it fails with
 * NoClassDefFoundError. This class is separate so that a missing plugin only disables one tool.
 */
final class Sparql {
    private static final String BASE = "urn:protege-mcp:snapshot";

    private Sparql() {
    }

    static Map<String, Object> run(String turtle, String query, int limit) throws Exception {
        SailRepository repo = new SailRepository(new MemoryStore());
        repo.initialize();
        try {
            RepositoryConnection conn = repo.getConnection();
            try {
                RDFParser parser = new TurtleParser();
                parser.setRDFHandler(new RDFInserter(conn));
                parser.parse(new StringReader(turtle), BASE);
                Query q = conn.prepareQuery(QueryLanguage.SPARQL, query);
                if (q instanceof BooleanQuery) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("result", ((BooleanQuery) q).evaluate());
                    return out;
                }
                if (q instanceof GraphQuery) {
                    return triples((GraphQuery) q, limit);
                }
                return table((TupleQuery) q, limit);
            } finally {
                conn.close();
            }
        } finally {
            repo.shutDown();
        }
    }

    private static Map<String, Object> table(TupleQuery q, int limit) throws Exception {
        TupleQueryResult r = q.evaluate();
        try {
            List<String> columns = r.getBindingNames();
            List<List<String>> rows = new ArrayList<>();
            int total = 0;
            while (r.hasNext()) {
                BindingSet b = r.next();
                total++;
                if (rows.size() < limit) {
                    List<String> row = new ArrayList<>();
                    for (String c : columns) {
                        Value v = b.getValue(c);
                        row.add(v == null ? null : v.stringValue());
                    }
                    rows.add(row);
                }
            }
            return result(columns, rows, total);
        } finally {
            r.close();
        }
    }

    private static Map<String, Object> triples(GraphQuery q, int limit) throws Exception {
        GraphQueryResult r = q.evaluate();
        try {
            List<List<String>> rows = new ArrayList<>();
            int total = 0;
            while (r.hasNext()) {
                Statement s = r.next();
                total++;
                if (rows.size() < limit) {
                    rows.add(List.of(s.getSubject().stringValue(), s.getPredicate().stringValue(),
                            s.getObject().stringValue()));
                }
            }
            return result(List.of("subject", "predicate", "object"), rows, total);
        } finally {
            r.close();
        }
    }

    private static Map<String, Object> result(List<String> columns, List<List<String>> rows, int total) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("total_rows", total);
        return out;
    }
}
