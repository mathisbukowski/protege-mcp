#!/usr/bin/env python3
"""End-to-end test against a running Protégé. It opens test/pizza.ofn and never saves over it."""
import json
import os
import sys
import tempfile
import urllib.error
import urllib.request

HOME = os.path.expanduser("~")
CFG = dict(l.strip().split("=", 1) for l in open(f"{HOME}/.Protege/mcp/config.properties")
           if "=" in l and not l.startswith("#"))
URL = f"http://127.0.0.1:{CFG.get('port', '8765')}/mcp"
HERE = os.path.dirname(os.path.abspath(__file__))
failures = []
next_id = [0]


def http(body, headers=None, method="POST"):
    h = {"Content-Type": "application/json", "Authorization": "Bearer " + CFG["token"]}
    h.update(headers or {})
    h = {k: v for k, v in h.items() if v is not None}
    req = urllib.request.Request(URL, data=None if body is None else json.dumps(body).encode(), headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def rpc(method, params=None):
    next_id[0] += 1
    status, text = http({"jsonrpc": "2.0", "id": next_id[0], "method": method, "params": params or {}})
    assert status == 200, (status, text)
    return json.loads(text)


def call(name, **args):
    r = rpc("tools/call", {"name": name, "arguments": args})["result"]
    text = r["content"][0]["text"]
    if r["isError"]:
        return {"error": text}
    try:
        return json.loads(text)
    except ValueError:
        return text


def check(label, ok, detail=None):
    print(("ok   " if ok else "FAIL ") + label + ("" if ok else f"  -> {detail}"))
    if not ok:
        failures.append(label)


def main():
    check("no token rejected", http({}, {"Authorization": None})[0] == 401)
    check("bad token rejected", http({}, {"Authorization": "Bearer nope"})[0] == 401)
    check("foreign Origin rejected", http({}, {"Origin": "http://evil.example"})[0] == 403)
    check("foreign Host rejected", http({}, {"Host": "evil.example"})[0] == 403)
    check("GET refused", http(None, method="GET")[0] == 405)
    check("notification accepted", http({"jsonrpc": "2.0", "method": "notifications/initialized"})[0] == 202)

    init = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {},
                              "clientInfo": {"name": "smoke", "version": "1"}})["result"]
    check("initialize echoes version", init["protocolVersion"] == "2025-06-18", init)
    tools = rpc("tools/list")["result"]["tools"]
    print(f"     {len(tools)} tools")
    check("unknown method", rpc("nope")["error"]["code"] == -32601)
    check("unknown tool", rpc("tools/call", {"name": "nope"})["error"]["code"] == -32602)

    r = call("open_ontology", location=f"{HERE}/pizza.ofn")
    check("open_ontology", r.get("ontology") == "http://example.org/pizza", r)
    w = {"workspace": r["workspace"]}

    r = call("status")
    win = r["windows"][w["workspace"]]
    check("status", any(o["active"] and o["iri"].endswith("pizza") for o in win["ontologies"]), r)
    print("     reasoners:", win["installed_reasoners"], "| windows:", len(r["windows"]))

    r = call("ontology_info", **w)
    check("ontology_info", r.get("classes") == 10 and r["axioms_by_type"]["SubClassOf"] == 10, r)
    print("     expressible in:", r.get("expressible_in"))

    r = call("search_entities", query="pizza", **w)
    check("search ranks exact first", r["results"][0]["iri"].endswith("#Pizza"), r)
    r = call("search_entities", query="fromage", **w)
    check("search by label", r["total"] == 1 and r["results"][0]["iri"].endswith("#Cheese"), r)

    r = call("describe_entity", entity="Pizza", **w)
    check("describe by label", "children" in r and len(r["children"]) >= 2, r)
    r = call("describe_entity", entity=":Margherita", render="functional", **w)
    check("describe by prefixed name", any("ObjectAllValuesFrom" in a for a in r["axioms"]), r)
    r = call("describe_entity", entity="Nope", **w)
    check("describe unknown is an error", "error" in r, r)

    r = call("get_hierarchy", **w)
    check("hierarchy", isinstance(r, str) and "      Margherita (+1)" in r, r)
    print("     " + r.replace("\n", "\n     ").rstrip())

    r = call("list_axioms", type="SubClassOf", limit=2, offset=1, **w)
    check("list_axioms pages", r["total"] == 10 and len(r["axioms"]) == 2, r)
    first = r["axioms"][0]
    print("     sample:", first)

    r = call("add_axioms", axioms=["SubClassOf(:Pizzza :Food)"], dry_run=True, **w)
    check("dry run reports typo as new entity", len(r["new_entities"]) == 1 and r["dry_run"], r)
    check("dry run changes nothing", "error" in call("describe_entity", entity="Pizzza", **w))
    r = call("add_axioms", axioms=["SubClassOf(:Calzone :Pizza)", "SubClassOf(:Pizza :Food)"], **w)
    check("add_axioms", r["added"] == 1 and r["already_present"] == 1 and len(r["new_entities"]) == 1, r)
    r = call("describe_entity", entity="Calzone", **w)
    check("hierarchy sees new class", r.get("parents") and "Pizza" in r["parents"][0], r)
    r = call("add_axioms", axioms=["SubClassOf(:Good :Food)", "NotAnAxiom(:x)"], **w)
    check("bad axiom names itself", "error" in r and "NotAnAxiom" in r["error"], r)
    check("bad batch adds nothing", "error" in call("describe_entity", entity="Good", **w))
    r = call("add_axioms", axioms=["Calzone SubClassOf hasTopping some Fromage"], syntax="manchester", **w)
    check("manchester axiom with short name and label", r.get("added") == 1, r)
    call("undo", **w)
    r = call("undo", **w)
    check("undo", r["steps_done"] == 1, r)
    check("undone", "error" in call("describe_entity", entity="Calzone", **w))
    call("redo", **w)
    check("redo", "error" not in call("describe_entity", entity="Calzone", **w))
    call("undo", **w)

    r = call("list_axioms", entity=":Pepperoni", type="SubClassOf", **w)
    rt = r["axioms"][0]
    r = call("remove_axioms", axioms=[rt], dry_run=True, **w)
    check("rendered axiom round-trips", r["removed"] == 1 and not r["not_found"], r)

    r = call("create_entities", entities=[
        {"kind": "class", "name": "Sauce", "parents": ["Food"], "label": "Sauce", "comment": "Liquid.", "lang": "en"},
        {"kind": "class", "name": "Tomato", "parents": ["Sauce"]},
        {"kind": "object_property", "name": "hasSauce", "domain": ["Pizza"], "range": ["Sauce"], "characteristics": ["functional"]},
        {"kind": "data_property", "name": "weight", "range": ["xsd:integer"], "characteristics": ["functional"]},
        {"kind": "individual", "name": "marinara", "types": ["Tomato"]},
    ], **w)
    check("create_entities", len(r.get("created", [])) == 5, r)
    r = call("describe_entity", entity="Tomato", **w)
    check("batch self-reference", r.get("parents") == ["Sauce"], r)
    call("undo", **w)
    check("create undone in one step", "error" in call("describe_entity", entity="Tomato", **w))

    r = call("rename_entity", entity="Lonely", new_name="Solitary", **w)
    check("rename", r.get("new_iri", "").endswith("#Solitary"), r)
    r = call("rename_entity", entity="Solitary", new_name="Pizza", **w)
    check("rename refuses merge", "error" in r, r)
    call("undo", **w)

    r = call("delete_entities", entities=["Pepperoni"], dry_run=True, **w)
    check("delete dry run", r["axioms_removed"] >= 3 and "axioms" in r, r)
    r = call("delete_entities", entities=["Pepperoni"], **w)
    check("delete", "error" in call("describe_entity", entity="Pepperoni", **w), r)
    call("undo", **w)
    check("delete undone", "error" not in call("describe_entity", entity="Pepperoni", **w))

    r = call("classify", **w)
    check("classify", r.get("status") == "INITIALIZED" and r.get("consistent") is True, r)
    check("finds unsatisfiable class", r.get("unsatisfiable_classes") == ["ImpossiblePizza"], r)

    r = call("dl_query", expression="Pizza and hasTopping some Cheese", queries=["instances"], **w)
    check("dl_query instances", r.get("instances") == ["myPizza"], r)
    r = call("dl_query", expression="Vegetarian", queries=["subclasses"], **w)
    check("dl_query inferred subclass", "Margherita" in r.get("subclasses", []), r)
    r = call("dl_query", expression="Pizza and nonsense", **w)
    check("dl_query bad expression", "error" in r, r)
    r = call("dl_query", expression="Thing", queries=["subclasses"], **w)
    check("dl_query Thing", "Pizza" in r.get("subclasses", []), r)
    r = call("get_hierarchy", inferred=True, root="Vegetarian", kind="class", **w)
    check("inferred hierarchy", isinstance(r, str) and "Margherita" in r, r)
    r = call("describe_entity", entity="Margherita", **w)
    check("describe shows inferences", "Vegetarian" in r.get("inferred_parents", []), r)

    r = call("explain", unsatisfiable_class="ImpossiblePizza", **w)
    just = r.get("justifications", [])
    check("explain finds justification", len(just) >= 1, r)
    if just:
        print("     " + "\n     ".join(just[0]["manchester"]))
        check("justification is minimal", len(just[0]["manchester"]) == 4, just[0])
    r = call("explain", axiom="SubClassOf(:Margherita :Vegetarian)", **w)
    check("explain entailment", len(r.get("justifications", [])) >= 1 and r.get("search_complete") is True, r)
    r = call("explain", axiom="SubClassOf(:Food :Pizza)", **w)
    check("explain non-entailment", "error" in r, r)
    r = call("explain", **w)
    check("explain consistent ontology", "error" in r, r)

    r = call("what_if", add=["SubClassOf(:Pepperoni :Vegetarian)"], **w)
    check("what_if unsat", r.get("newly_unsatisfiable") == ["Pepperoni"], r)
    r = call("what_if", remove=["SubClassOf(:ImpossiblePizza ObjectSomeValuesFrom(:hasTopping :Meat))"], **w)
    check("what_if repair", r.get("no_longer_unsatisfiable") == ["ImpossiblePizza"], r)
    print("     gained:", r.get("subsumptions_gained"))
    r = call("what_if", add=["ClassAssertion(:Meat :mozzarella)"], **w)
    check("what_if inconsistency", r.get("consistent_after") is False, r)
    r = call("status")["windows"][w["workspace"]]
    check("what_if left reasoner in sync", r["reasoner_status"] == "INITIALIZED", r)

    r = call("sparql_query", query="SELECT ?c WHERE { ?c rdfs:subClassOf :Food } ORDER BY ?c", **w)
    check("sparql select", isinstance(r, dict) and r.get("total_rows") == 2, r)
    r = call("sparql_query", query="ASK { :Pizza rdfs:subClassOf :Food }", **w)
    check("sparql ask", r.get("result") is True, r)
    r = call("sparql_query", query="CONSTRUCT { ?s a :Thing } WHERE { ?s a owl:Class }", limit=3, **w)
    check("sparql construct", len(r.get("rows", [])) == 3, r)
    r = call("sparql_query", query="DELETE WHERE { ?s ?p ?o }", **w)
    check("sparql update refused", "error" in r, r)
    r = call("sparql_query", query="SELECT oops", **w)
    check("sparql syntax error", "error" in r, r)

    r = call("audit", **w)
    check("audit duplicates", r.get("duplicate_labels", {}).get("count") == 1, r)
    check("audit orphan", r.get("classes_in_no_logical_axiom", {}).get("count") == 1, r)
    check("audit profiles", r.get("owl2_profiles", {}).get("OWL2_DL", {}).get("conforms") is True, r)

    r = call("show_entity", entity="Margherita", **w)
    check("show_entity", r.get("name") == "Margherita", r)
    r = call("describe_entity", **w)
    check("describe defaults to selection", r.get("name") == "Margherita", r)

    out = tempfile.mkdtemp(prefix="protege-mcp-")
    path = f"{out}/saved.ttl"
    r = call("save_ontology", path=path, format="turtle", **w)
    check("save as turtle", os.path.getsize(path) > 500, r)
    r = call("save_ontology", path=f"{HERE}/pizza.ofn", **w)
    check("save refuses to overwrite", "error" in r, r)
    r = call("save_ontology", path="relative.owl", **w)
    check("save refuses relative path", "error" in r, r)
    r = call("status")["windows"][w["workspace"]]
    check("no unsaved changes", not r["ontologies"][0]["unsaved_changes"], r)

    print(f"\n{len(failures)} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
